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
 * 表2：视频任务流水核心主表实体 (video_task_record)
 * 仅保留轻量级元数据与状态机，剥离万字转写稿与AI大JSON，保障主表极速流转与索引查询性能。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_task_record")
public class VideoTaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String fileName;

    private Long totalSize;

    private Integer totalChunks;

    /** 视频文件的32位十六进制MD5内容指纹 */
    private String videoMd5;

    /** 当前一次分析目标原文；上传完成时为空 */
    private String userGoal;

    /** 当前一次分析目标的SHA-256摘要，用于目标级幂等和结果隔离 */
    private String goalDigest;

    /** MinIO存储对象路径，例如 videos/{md5}/{fileName} */
    private String fileUrl;

    /** 状态机：0-待分析，1-排队中，2-处理中，3-已完成，4-失败，5-合并中 */
    private Integer status;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
