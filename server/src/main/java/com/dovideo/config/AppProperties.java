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

    /**
     * AI 能力配置：ASR（语音转写）与 LLM（结构化总结）完全独立配置，
     * 因此可以自由组合不同厂商，例如 ASR 用硅基流动 SenseVoice，LLM 用 DeepSeek 官方 API。
     */
    @Data
    public static class AiProps {
        private int maxRetries = 3;
        private long initialBackoffMs = 1000L;
        private String ffmpegPath = "ffmpeg";
        private AsrProps asr = new AsrProps();
        private LlmProps llm = new LlmProps();
    }

    @Data
    public static class AsrProps {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "demo-key";
        private String model = "FunAudioLLM/SenseVoiceSmall";
        private String language = "zh";
    }

    @Data
    public static class LlmProps {
        /** DeepSeek 官方 API 地址；若使用硅基流动网关则改为 https://api.siliconflow.cn/v1 */
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "demo-key";
        private String model = "deepseek-chat";
        private double temperature = 0.3;
        private int maxTokens = 4096;
    }
}
