package com.dovideo.util;

import com.dovideo.client.AiClientException;
import com.dovideo.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * 通用指数退避重试器（对应简历亮点5 & 《项目拷打》PDF第34~35页）：
 * 针对网络超时、HTTP 429、HTTP 5xx 等瞬时异常，按 1s -> 2s -> 4s 指数退避重试；
 * 对 400/401/403 等永久异常立即失败，绝不浪费重试次数。
 */
@Slf4j
@Component
public class ExponentialBackoffRetry {

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final Sleeper sleeper;

    @Autowired
    public ExponentialBackoffRetry(AppProperties appProperties) {
        this(
                appProperties.getAi().getMaxRetries(),
                appProperties.getAi().getInitialBackoffMs(),
                Thread::sleep
        );
    }

    /** 供单元测试注入虚拟时钟，避免真实 sleep */
    public ExponentialBackoffRetry(int maxAttempts, long initialBackoffMs, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.sleeper = sleeper;
    }

    public <T> T execute(String operation, Supplier<T> action) {
        long backoff = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw ex;
                }
                log.warn("exponential_backoff_retry operation={} attempt={}/{} backoffMs={} cause={}",
                        operation, attempt, maxAttempts, backoff, ex.getMessage());
                sleepQuietly(backoff);
                backoff *= 2;
            }
        }
    }

    /**
     * 仅瞬时故障可重试；永久错误（参数非法、鉴权失败、资源不存在）立即失败。
     */
    public static boolean isRetryable(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof AiClientException aiEx) {
                return aiEx.isRetryable();
            }
            if (current instanceof IOException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException("重试等待被中断", false, null, e);
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
