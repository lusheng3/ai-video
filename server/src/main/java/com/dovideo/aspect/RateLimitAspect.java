package com.dovideo.aspect;

import com.dovideo.annotation.RateLimit;
import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.enums.AccountTypeEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.service.AnalysisMetrics;
import com.dovideo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

/**
 * 令牌桶限流切面（对应简历亮点5 & 《项目拷打》PDF第29~31页）：
 * 基于 Redis Hash 结构存储桶状态（tokens, last_time），通过 Lua 脚本在单次原子调用中完成：
 * 1. 惰性计算补充令牌：(now - last_time) * rate
 * 2. 判断 tokens >= 1
 * 3. 原子扣减并刷新 60 秒过期时间
 * 被限流的请求直接抛出 BusinessException(ErrorCode.RATE_LIMITED)，向前端返回 HTTP 429。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    public static final String KEY_PREFIX = "rate_limiter:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final AnalysisMetrics metrics;
    private final DefaultRedisScript<Long> tokenBucketScript;

    public RateLimitAspect(StringRedisTemplate redisTemplate,
                           AppProperties appProperties,
                           AnalysisMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
        this.metrics = metrics;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        this.tokenBucketScript.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        AppProperties.RateLimitProps props = appProperties.getRateLimit();
        if (!props.isEnabled()) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = currentRequest();
        String apiPath = (rateLimit.apiPath() != null && !rateLimit.apiPath().isBlank())
                ? rateLimit.apiPath()
                : (request != null ? request.getRequestURI() : joinPoint.getSignature().getName());

        Object userIdAttr = request != null ? request.getAttribute(AuthService.ATTR_USER_ID) : null;
        Object accountTypeAttr = request != null ? request.getAttribute(AuthService.ATTR_ACCOUNT_TYPE) : null;

        String subject = userIdAttr != null ? String.valueOf(userIdAttr) : "anonymous";
        boolean isVip = accountTypeAttr instanceof Integer type && type == AccountTypeEnum.VIP.getCode();

        // 优先级：若注解上显式指定了 rate / capacity (> 0)，优先使用接口自定义参数；
        // 否则按 user_info.account_type 使用全局免费/VIP默认配额（VIP用户还可在自定义参数基础上翻倍）
        double baseRate = rateLimit.rate() > 0
                ? rateLimit.rate()
                : (isVip ? props.getVipUserRatePerSec() : props.getFreeUserRatePerSec());
        int baseCapacity = rateLimit.capacity() > 0
                ? rateLimit.capacity()
                : (isVip ? props.getVipUserCapacity() : props.getFreeUserCapacity());

        boolean allowed = tryAcquire(apiPath, subject, baseRate, baseCapacity, props.getExpireSeconds());
        if (!allowed) {
            metrics.rateLimited(apiPath);
            log.warn("rate_limit_exceeded apiPath={} userId={} rate={} capacity={}",
                    apiPath, subject, baseRate, baseCapacity);
            throw new BusinessException(ErrorCode.RATE_LIMITED, "当前请求过多，请稍后再试");
        }

        return joinPoint.proceed();
    }

    /**
     * 执行 Lua 脚本原子获取令牌
     */
    public boolean tryAcquire(String apiPath, String userId, double rate, int capacity, int expireSeconds) {
        String redisKey = KEY_PREFIX + apiPath + ":" + userId;
        long nowMs = System.currentTimeMillis();

        Long result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(redisKey),
                String.valueOf(rate),
                String.valueOf(capacity),
                String.valueOf(nowMs),
                String.valueOf(expireSeconds)
        );
        return result != null && result == 1L;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }
}
