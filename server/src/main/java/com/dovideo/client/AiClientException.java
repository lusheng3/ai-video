package com.dovideo.client;

import lombok.Getter;

/**
 * 第三方 AI 调用异常。
 * retryable = true：网络超时、连接失败、HTTP 429、HTTP 5xx → 可指数退避重试；
 * retryable = false：HTTP 400/401/403/404、JSON 解析失败 → 永久错误，立即失败不浪费重试次数。
 */
@Getter
public class AiClientException extends RuntimeException {

    private final boolean retryable;
    private final Integer httpStatus;

    public AiClientException(String message, boolean retryable) {
        this(message, retryable, null, null);
    }

    public AiClientException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }
}
