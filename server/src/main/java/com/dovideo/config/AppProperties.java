package com.dovideo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean mockEnabled = true;
    private long mockProcessDelayMs = 1500L;

    private MinioProps minio = new MinioProps();
    private UploadProps upload = new UploadProps();
    private RateLimitProps rateLimit = new RateLimitProps();
    private MqProps mq = new MqProps();
    private AiProps ai = new AiProps();

    @Data
    public static class MinioProps {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin123";
        private String bucketName = "dovideo-private";
        private int presignExpiryMinutes = 5;
        private String tempChunkPrefix = "temp_chunks/";
        private String videoPrefix = "videos/";
        private int lifecycleExpireDays = 1;
    }

    @Data
    public static class UploadProps {
        /** 默认分片大小 5MB（匹配 MinIO composeObject 非末片最小 5MB 限制） */
        private long chunkSizeBytes = 5L * 1024 * 1024;
        /** Redis 分片进度 Set 的过期时间（小时） */
        private int redisTtlHours = 24;
    }

    @Data
    public static class RateLimitProps {
        private boolean enabled = true;
        private double freeUserRatePerSec = 1.0;
        private int freeUserCapacity = 5;
        private double vipUserRatePerSec = 5.0;
        private int vipUserCapacity = 20;
        private int expireSeconds = 60;
    }

    @Data
    public static class MqProps {
        private String analysisTopic = "video-analysis-topic";
        private String consumerGroup = "video-analysis-group";
        private String dlqTopic = "%DLQ%video-analysis-group";
        private String customDlqTopic = "video-analysis-dlq-topic";
        private int maxReconsumeTimes = 3;
    }

    @Data
    public static class AiProps {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "demo-key";
        private String asrModel = "FunAudioLLM/SenseVoiceSmall";
        private String llmModel = "deepseek-ai/DeepSeek-V3";
        private int maxRetries = 3;
        private long initialBackoffMs = 1000L;
    }
}
