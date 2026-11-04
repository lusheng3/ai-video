package com.dovideo.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiClientConfig {

    /**
     * 调用第三方 AI 服务的 HTTP 客户端：连接 10s、读取 120s（长逐字稿总结可能较慢）、写入 60s
     */
    @Bean
    public OkHttpClient aiHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(60))
                .build();
    }
}
