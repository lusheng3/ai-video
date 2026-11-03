package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
                // MinIO 成品文件已存在 -> 自动推进状态为“已上传待分析(0)”并清理临时分片与 Redis Set
                record.setStatus(TaskStatusEnum.UPLOADED.getCode());
                record.setUpdateTime(LocalDateTime.now());
                taskRecordMapper.updateById(record);

                redisTemplate.delete(UploadService.UPLOAD_SET_KEY_PREFIX + record.getVideoMd5());
                if (record.getTotalChunks() != null && record.getTotalChunks() > 0) {
                    minioService.removeChunkObjects(record.getVideoMd5(), record.getTotalChunks());
                }
                repairedCount++;
                log.info("upload_merge_reconciled_to_uploaded taskId={} videoMd5={}",
                        record.getId(), record.getVideoMd5());
            } else {
                // MinIO 成品文件不存在 -> 标记为合并失败，保留 MinIO 中的分片供前端直接重试 /merge
                record.setStatus(TaskStatusEnum.FAILED.getCode());
                record.setErrorMsg("分片合并中断，请重新触发合并");
                record.setUpdateTime(LocalDateTime.now());
                taskRecordMapper.updateById(record);
                log.warn("upload_merge_reconciled_to_failed taskId={} videoMd5={}",
                        record.getId(), record.getVideoMd5());
            }
        }
        return repairedCount;
    }
}
