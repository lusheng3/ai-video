package com.dovideo.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于 Redis Hash + Lua 惰性计算的令牌桶限流注解（对应简历亮点5）
 * 支持两种用法：
 * 1. @RateLimit —— 不传参数，自动按 application.yml 中的免费/VIP用户默认配额限流
 * 2. @RateLimit(rate = 20.0, capacity = 50) —— 为特定接口单独定制生成速率与桶容量
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 接口路径标识（为空时默认取当前 HTTP 请求 URI，构成 rate_limiter:{api_path}:{userId}）
     */
    String apiPath() default "";

    /**
     * 当前接口专属的令牌生成速率（个/秒）。默认 -1 表示使用配置文件中的全局默认速率。
     */
    double rate() default -1.0;

    /**
     * 当前接口专属的令牌桶最大容量。默认 -1 表示使用配置文件中的全局默认容量。
     */
    int capacity() default -1;
}
