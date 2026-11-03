package com.dovideo.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于 Redis Hash + Lua 惰性计算的令牌桶限流注解（对应简历亮点5）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 接口路径标识（为空时默认取当前 HTTP 请求 URI，构成 rate_limiter:{api_path}:{userId}）
     */
    String apiPath() default "";
}
