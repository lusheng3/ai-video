package com.dovideo.enums;

import lombok.Getter;

/**
 * 视频任务生命周期状态机（对应数据库 TINYINT status 字段）
 */
@Getter
public enum TaskStatusEnum {

    UPLOADED(0, "UPLOADED", "待分析"),
    QUEUED(1, "QUEUED", "排队中"),
    PROCESSING(2, "PROCESSING", "处理中"),
    SUCCESS(3, "SUCCESS", "已完成"),
    FAILED(4, "FAILED", "处理失败"),
    MERGING(5, "MERGING", "分片合并中");

    private final int code;
    private final String redisLabel;
    private final String description;

    TaskStatusEnum(int code, String redisLabel, String description) {
        this.code = code;
        this.redisLabel = redisLabel;
        this.description = description;
    }

    public static TaskStatusEnum fromCode(Integer code) {
        if (code == null) {
            return UPLOADED;
        }
        for (TaskStatusEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UPLOADED;
    }
}
