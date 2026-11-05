package com.dovideo.enums;

import lombok.Getter;

/**
 * 异步分析阶段机：消费者每进入一个阶段就推进一次，SSE / 轮询降级都读取同一份阶段语义。
 *
 * <p>阶段与任务主状态（{@link TaskStatusEnum}）是两层模型：
 * 主状态只有 排队/处理/完成/失败，阶段则描述「处理中具体在做什么」，
 * 既能让前端显示细粒度进度，又不会污染数据库状态机。
 */
@Getter
public enum AnalysisStageEnum {

    QUEUED("任务已进入异步分析队列", 0, TaskStatusEnum.QUEUED),
    CONSUMING("消费者已接手，正在准备解析", 5, TaskStatusEnum.PROCESSING),
    AUDIO_EXTRACTING("正在下载视频并提取音频", 10, TaskStatusEnum.PROCESSING),
    AUDIO_SEGMENTED("音频分段完成，开始逐段转写", 20, TaskStatusEnum.PROCESSING),
    ASR("正在逐段语音转写", 30, TaskStatusEnum.PROCESSING),
    LLM("正在生成结构化总结", 78, TaskStatusEnum.PROCESSING),
    PERSISTING("正在保存逐字稿与结构化结果", 92, TaskStatusEnum.PROCESSING),
    COMPLETED("分析完成", 100, TaskStatusEnum.SUCCESS),
    REUSED("命中已有分析结果，直接复用", 100, TaskStatusEnum.SUCCESS),
    RETRYING("本次执行失败，等待消息队列自动重试", 0, TaskStatusEnum.PROCESSING),
    FAILED("分析失败", 0, TaskStatusEnum.FAILED);

    private final String defaultMessage;
    private final int defaultProgress;
    private final TaskStatusEnum taskStatus;

    AnalysisStageEnum(String defaultMessage, int defaultProgress, TaskStatusEnum taskStatus) {
        this.defaultMessage = defaultMessage;
        this.defaultProgress = defaultProgress;
        this.taskStatus = taskStatus;
    }

    /** 对外暴露的是 TINYINT 状态码，保持与数据库 / 前端既有协议一致。 */
    public int getStatus() {
        return taskStatus.getCode();
    }

    public String getStatusLabel() {
        return taskStatus.getRedisLabel();
    }

    public static AnalysisStageEnum from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (AnalysisStageEnum stage : values()) {
            if (stage.name().equalsIgnoreCase(value)) {
                return stage;
            }
        }
        return null;
    }
}
