package com.dovideo.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    BAD_REQUEST(400, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    CHUNK_CORRUPTED(400, HttpStatus.BAD_REQUEST, "分片MD5校验失败，数据已损坏，请重传该分片"),
    FILE_MD5_MISMATCH(400, HttpStatus.BAD_REQUEST, "完整文件MD5校验失败，数据已损坏，请重新上传"),
    UNAUTHORIZED(401, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(403, HttpStatus.FORBIDDEN, "无权访问该资源"),
    NOT_FOUND(404, HttpStatus.NOT_FOUND, "请求的资源不存在"),
    CONFLICT(409, HttpStatus.CONFLICT, "任务状态冲突或正在处理中"),
    RATE_LIMITED(429, HttpStatus.TOO_MANY_REQUESTS, "当前请求过多，请稍后再试"),
    SERVICE_UNAVAILABLE(503, HttpStatus.SERVICE_UNAVAILABLE, "系统繁忙，请重试"),
    INTERNAL_ERROR(500, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
