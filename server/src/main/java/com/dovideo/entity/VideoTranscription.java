package com.dovideo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表3：原始转录明细表实体 (video_transcription) —— 垂直分表
 * 独立存放 LONGTEXT 逐字稿，并对 video_md5 / task_id 建立唯一索引作为底层物理防重底线。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_transcription")
public class VideoTranscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String videoMd5;

    private String transcriptionContent;

    private Integer wordCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
