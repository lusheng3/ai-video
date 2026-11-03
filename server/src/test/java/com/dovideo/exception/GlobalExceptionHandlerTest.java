package com.dovideo.exception;

import com.dovideo.common.ErrorCode;
import com.dovideo.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapRateLimitedToHttp429() {
        BusinessException ex = new BusinessException(ErrorCode.RATE_LIMITED, "当前请求过多，请稍后再试");
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(429, response.getBody().getCode());
        assertEquals("当前请求过多，请稍后再试", response.getBody().getMessage());
    }

    @Test
    void shouldMapConflictToHttp409() {
        BusinessException ex = new BusinessException(ErrorCode.CONFLICT, "该文件正在合并中");
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().getCode());
    }

    @Test
    void shouldMapServiceUnavailableToHttp503() {
        BusinessException ex = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请重试");
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().getCode());
    }
}
