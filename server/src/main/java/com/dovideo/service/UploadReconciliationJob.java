package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分片合并最终一致性后台校准任务（对应《项目拷打》PDF第26~27页改进方案）：
 * 解决“MinIO composeObject 已经合并成功，但紧接着更新 MySQL 状态时数据库宕机或网络中断”导致的数据不一致问题。
 * 定期扫描状态为 5（合并中）且超时卡住的记录：
 * - 若 MinIO 中目标文件已存在：说明只是后续更新步骤失败，自动补齐状态为 0（待分析）并清理分片与 Redis 缓存；
 * - 若 MinIO 中目标文件不存在：说明核心合并步骤失败，将状态标记为失败或清理，以便前端重新触发 /merge。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadReconciliationJob {

    private final VideoTaskRecordMapper taskRecordMapper;
    private final MinioService minioService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 每 10 分钟巡检一次卡在“合并中(status=5)”超过 5 分钟的任务（走 idx_status_create 联合索引）
     */
    @Scheduled(fixedDelayString = "${app.upload.reconcile-interval-ms:600000}")
    public int reconcileStuckMergingTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<VideoTaskRecord> stuckRecords = taskRecordMapper.selectList(
                new LambdaQueryWrapper<VideoTaskRecord>()
                        .eq(VideoTaskRecord::getStatus, TaskStatusEnum.MERGING.getCode())
                        .lt(VideoTaskRecord::getCreateTime, threshold));

        int repairedCount = 0;
        for (VideoTaskRecord record : stuckRecords) {
            if (minioService.objectExists(record.getFileUrl())) {
                String actualMd5;
                try {
                    actualMd5 = minioService.calculateObjectMd5(record.getFileUrl());
                } catch (RuntimeException ex) {
                    // MinIO 暂时不可读时保留“合并中”，下一轮巡检继续校验，不能绕过 MD5 对拍。
                    log.warn("upload_merge_reconcile_md5_read_failed taskId={} videoMd5={}",
                            record.getId(), record.getVideoMd5(), ex);
                    continue;
                }

                if (record.getVideoMd5().equalsIgnoreCase(actualMd5)) {
                    // MinIO 成品文件存在且完整 MD5 一致，才允许推进为“已上传待分析”。
                    taskRecordMapper.update(null, new UpdateWrapper<VideoTaskRecord>()
                            .eq("id", record.getId())
                            .set("status", TaskStatusEnum.UPLOADED.getCode())
                            .set("error_msg", null)
                            .set("update_time", LocalDateTime.now()));
                    clearUploadArtifacts(record);
                    repairedCount++;
                    log.info("upload_merge_reconciled_to_uploaded taskId={} videoMd5={}",
                            record.getId(), record.getVideoMd5());
                } else {
                    discardInvalidObject(record, actualMd5);
                    repairedCount++;
                    log.error("upload_merge_reconciled_md5_mismatch taskId={} videoMd5={} actualMd5={}",
                            record.getId(), record.getVideoMd5(), actualMd5);
                }
            } else {
                // MinIO 成品文件不存在 -> 标记失败，保留分片供前端直接重试 /merge
                markTaskFailed(record, "分片合并中断，请重新触发合并", false);
                log.warn("upload_merge_reconciled_to_failed taskId={} videoMd5={}",
                        record.getId(), record.getVideoMd5());
            }
        }
        return repairedCount;
    }

    private void discardInvalidObject(VideoTaskRecord record, String actualMd5) {
        try {
            minioService.removeObject(record.getFileUrl());
        } catch (RuntimeException ex) {
            log.error("reconcile_invalid_object_cleanup_failed taskId={} objectKey={}",
                    record.getId(), record.getFileUrl(), ex);
        }
        markTaskFailed(record, "完整文件内容校验失败，请重新上传", true);
    }

    private void markTaskFailed(VideoTaskRecord record, String message, boolean clearChunks) {
        taskRecordMapper.update(null, new UpdateWrapper<VideoTaskRecord>()
                .eq("id", record.getId())
                .set("file_url", null)
                .set("status", TaskStatusEnum.FAILED.getCode())
                .set("error_msg", message)
                .set("update_time", LocalDateTime.now()));
        if (clearChunks) {
            clearUploadArtifacts(record);
        }
    }

    private void clearUploadArtifacts(VideoTaskRecord record) {
        try {
            redisTemplate.delete(UploadService.UPLOAD_SET_KEY_PREFIX + record.getVideoMd5());
        } catch (RuntimeException ex) {
            log.warn("reconcile_upload_redis_cleanup_failed taskId={} videoMd5={}",
                    record.getId(), record.getVideoMd5(), ex);
        }
        if (record.getTotalChunks() != null && record.getTotalChunks() > 0) {
            minioService.removeChunkObjects(record.getVideoMd5(), record.getTotalChunks());
        }
    }
}
