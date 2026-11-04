package com.dovideo.util;

import com.dovideo.client.AiClientException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExponentialBackoffRetryTest {

    @Test
    void shouldRetryWithExponentialBackoffAndEventuallySucceed() {
        List<Long> sleeps = new ArrayList<>();
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(3, 1000L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute("test-op", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AiClientException("HTTP 429", true, 429, null);
            }
            return "OK";
        });

        assertEquals("OK", result);
        assertEquals(3, attempts.get());
        // 前两次失败分别等待 1000ms、2000ms，第三次成功不再等待
        assertEquals(List.of(1000L, 2000L), sleeps);
    }

    @Test
    void shouldFailImmediatelyOnPermanentErrorWithoutRetry() {
        List<Long> sleeps = new ArrayList<>();
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(3, 1000L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        AiClientException ex = assertThrows(AiClientException.class, () ->
                retry.execute("test-op", () -> {
                    attempts.incrementAndGet();
                    throw new AiClientException("HTTP 401 Unauthorized", false, 401, null);
                }));

        assertEquals(401, ex.getHttpStatus());
        assertEquals(1, attempts.get());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void shouldStopAfterMaxAttemptsWhenAlwaysRetryable() {
        List<Long> sleeps = new ArrayList<>();
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(3, 1000L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(AiClientException.class, () ->
                retry.execute("test-op", () -> {
                    attempts.incrementAndGet();
                    throw new AiClientException("HTTP 503", true, 503, null);
                }));

        assertEquals(3, attempts.get());
        assertEquals(List.of(1000L, 2000L), sleeps);
    }
}
