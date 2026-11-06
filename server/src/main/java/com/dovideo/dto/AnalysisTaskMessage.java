package com.dovideo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 投递到 RocketMQ 视频分析专属 Topic 的轻量消息体（仅几百字节，绝不携带视频本体）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisTaskMessage implements Serializable {

    private Long taskId;
    private Long userId;
    private String videoMd5;
    /** MinIO 内的对象 Key，如 videos/{md5}/course.mp4 */
    private String minioPath;
    private String userGoal;
    private String goalDigest;
}
