package com.dovideo.aspect;

import com.dovideo.annotation.RateLimit;
import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.service.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.dovideo.exception.BusinessException;
import com.dovideo.service.AuthService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private AppProperties appProperties;
    private RateLimitAspect rateLimitAspect;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        rateLimitAspect = new RateLimitAspect(
                redisTemplate, appProperties, new AnalysisMetrics(new SimpleMeterRegistry()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/analysis/submit");
        request.setAttribute(AuthService.ATTR_USER_ID, 1001L);
        request.setAttribute(AuthService.ATTR_ACCOUNT_TYPE, 0);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldAllowRequestWhenLuaTokenBucketReturnsOne() throws Throwable {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(1L);
        when(joinPoint.proceed()).thenReturn("SUCCESS_RESPONSE");
        RateLimit annotation = mockRateLimit("/api/analysis/submit", -1.0, -1);

        Object result = rateLimitAspect.around(joinPoint, annotation);

        assertEquals("SUCCESS_RESPONSE", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keysCaptor.capture(),
                eq("1.0"),
                eq("5"),
                any(),
                eq("60"));
        assertEquals("rate_limiter:/api/analysis/submit:1001", keysCaptor.getValue().get(0));
    }

    @Test
    void shouldUseCustomRateAndCapacityWhenSpecifiedOnAnnotation() throws Throwable {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(1L);
        when(joinPoint.proceed()).thenReturn("CHUNK_OK");
        RateLimit annotation = mockRateLimit("/api/upload/chunk", 20.0, 50);

        Object result = rateLimitAspect.around(joinPoint, annotation);

        assertEquals("CHUNK_OK", result);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                eq("20.0"),
                eq("50"),
                any(),
                eq("60"));
    }

    @Test
    void shouldThrowRateLimited429WhenLuaTokenBucketReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(0L);
        RateLimit annotation = mockRateLimit("/api/analysis/submit", -1.0, -1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimitAspect.around(joinPoint, annotation));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertEquals(429, ex.getErrorCode().getCode());
    }

    private RateLimit mockRateLimit(String path, double rate, int capacity) {
        RateLimit rateLimit = mock(RateLimit.class);
        when(rateLimit.apiPath()).thenReturn(path);
        when(rateLimit.rate()).thenReturn(rate);
        when(rateLimit.capacity()).thenReturn(capacity);
        return rateLimit;
    }
}
