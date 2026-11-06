package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.UploadCheckResponse;
import com.dovideo.dto.UploadMergeRequest;
import com.dovideo.dto.UploadMergeResponse;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.VideoTaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 分片上传、秒传、Redis Set 断点续传、composeObject 合并与完整 MD5 对拍服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    /** 记录已上传分片序号的 Redis Set Key 前缀：upload:task:{file_md5} */
    public static final String UPLOAD_SET_KEY_PREFIX = "upload:task:";
    /** 前端触发 /merge 时的分布式合并锁 Key 前缀：dovideo:merge:{file_md5} */
    public static final String MERGE_LOCK_KEY_PREFIX = "dovideo:merge:";

    private final VideoTaskRecordMapper taskRecordMapper;
    private final MinioService minioService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final AppProperties appProperties;

    /**
     * 1. Check预检请求（秒传 / 断点续传 / 新任务）
     */
    public UploadCheckResponse checkUpload(String fileMd5,
                                           String fileName,
                                           Long totalSize,
                                           Integer totalChunks,
                                           Long userId) {
        String normalizedMd5 = normalizeMd5(fileMd5);

        // 【情况1：秒传判定】查 MySQL 是否已存在该 MD5 且已合并成功的记录（status != 5合并中 且 fileUrl 非空）
        VideoTaskRecord existing = taskRecordMapper.selectOne(
                new LambdaQueryWrapper<VideoTaskRecord>()
                        .eq(VideoTaskRecord::getUserId, userId)
                        .eq(VideoTaskRecord::getVideoMd5, normalizedMd5)
                        .isNotNull(VideoTaskRecord::getFileUrl)
                        .in(VideoTaskRecord::getStatus,
                                TaskStatusEnum.UPLOADED.getCode(),
                                TaskStatusEnum.SUCCESS.getCode())
                        .orderByDesc(VideoTaskRecord::getId)
                        .last("LIMIT 1"));

        if (existing != null) {
            log.info("upload_check_deduplicated fileMd5={} userId={} taskId={}",
                    normalizedMd5, userId, existing.getId());
            return UploadCheckResponse.builder()
                    .deduplicated(true)
                    .taskId(existing.getId())
                    .fileUrl(existing.getFileUrl())
                    .uploadedChunks(Collections.emptySet())
                    .build();
        }

        // 【情况2 & 3：断点续传或新任务】从 Redis Set 取出已成功落盘的分片序号集合
        Set<Integer> uploadedChunks = getUploadedChunkIndexes(normalizedMd5);
        return UploadCheckResponse.builder()
                .deduplicated(false)
                .taskId(null)
                .fileUrl(null)
                .uploadedChunks(uploadedChunks)
                .build();
    }

    /**
     * 2. 上传单个分片（严格遵循“先校验MD5 -> 先落盘MinIO -> 后记账Redis Set”）
     */
    public void uploadChunk(String fileMd5,
                            int chunkIndex,
                            int totalChunks,
                            String chunkMd5,
                            MultipartFile file) {
        String normalizedMd5 = normalizeMd5(fileMd5);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传的分片文件不能为空");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分片序号越界");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取分片字节流失败", e);
        }

        // 若前端携带了分片MD5，则在服务端重算一次比对，防止弱网传输发生比特翻转损坏
        if (chunkMd5 != null && !chunkMd5.isBlank()) {
            String calculatedChunkMd5 = calculateMd5Hex(bytes);
            if (!calculatedChunkMd5.equalsIgnoreCase(chunkMd5.trim())) {
                log.warn("chunk_md5_mismatch fileMd5={} chunkIndex={} expected={} actual={}",
                        normalizedMd5, chunkIndex, chunkMd5, calculatedChunkMd5);
                throw new BusinessException(ErrorCode.CHUNK_CORRUPTED);
            }
        }

        // 第一步（先落盘）：将分片写入 MinIO temp_chunks/{fileMd5}/{chunkIndex}
        minioService.putChunkObject(
                normalizedMd5,
                chunkIndex,
                new ByteArrayInputStream(bytes),
                bytes.length);

        // 第二步（后记账）：只有 MinIO 保存成功后，才往 Redis Set 记录该分片序号，并刷新 24h TTL
        // Set 天然去重：弱网下前端重发同一分片 N 次，Set 中也只有一条记录，SCARD 计数绝对准确
        String redisSetKey = uploadSetKey(normalizedMd5);
        redisTemplate.opsForSet().add(redisSetKey, String.valueOf(chunkIndex));
        redisTemplate.expire(redisSetKey, Duration.ofHours(appProperties.getUpload().getRedisTtlHours()));
    }

    /**
     * 3. 前端当总指挥触发 /merge 合并流程：
     * 合并锁 + 状态检查幂等 -> 校验 Redis Set 分片总数 -> 标记"合并中(5)" -> MinIO composeObject -> 更新完成(0)
     */
    public UploadMergeResponse mergeChunks(UploadMergeRequest request, Long userId) {
        String normalizedMd5 = normalizeMd5(request.getFileMd5());
        String lockKey = MERGE_LOCK_KEY_PREFIX + normalizedMd5;
        RLock mergeLock = redissonClient.getLock(lockKey);

        boolean locked = mergeLock.tryLock();
        if (!locked) {
            throw new BusinessException(ErrorCode.CONFLICT, "该视频分片正在合并中，请勿重复提交");
        }

        try {
            // 【锁内幂等检查】若数据库里当前用户已有该 MD5 合并完成的记录，直接返回结果，不做重复合并
            VideoTaskRecord completedRecord = taskRecordMapper.selectOne(
                    new LambdaQueryWrapper<VideoTaskRecord>()
                            .eq(VideoTaskRecord::getUserId, userId)
                            .eq(VideoTaskRecord::getVideoMd5, normalizedMd5)
                            .isNotNull(VideoTaskRecord::getFileUrl)
                            .in(VideoTaskRecord::getStatus,
                                    TaskStatusEnum.UPLOADED.getCode(),
                                    TaskStatusEnum.SUCCESS.getCode())
                            .orderByDesc(VideoTaskRecord::getId)
                            .last("LIMIT 1"));
            if (completedRecord != null) {
                return UploadMergeResponse.builder()
                        .taskId(completedRecord.getId())
                        .videoMd5(normalizedMd5)
                        .fileUrl(completedRecord.getFileUrl())
                        .status(completedRecord.getStatus())
                        .build();
            }

            // 【校验分片数量】检查 Redis Set 已上传分片数是否等于预期总分片数
            String redisSetKey = uploadSetKey(normalizedMd5);
            Long uploadedCount = redisTemplate.opsForSet().size(redisSetKey);
            int actualCount = uploadedCount == null ? 0 : uploadedCount.intValue();
            if (actualCount != request.getTotalChunks()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "分片尚未全部上传完成（已上传 " + actualCount + "/" + request.getTotalChunks() + "）");
            }

            // 【引入中间状态 5-合并中】先写库记录"合并中"与目标路径，配合后台校准任务保证最终一致性
            String expectedTargetKey = minioService.videoObjectKey(normalizedMd5, request.getFileName());
            VideoTaskRecord taskRecord = VideoTaskRecord.builder()
                    .userId(userId)
                    .fileName(request.getFileName())
                    .totalSize(request.getTotalSize())
                    .totalChunks(request.getTotalChunks())
                    .videoMd5(normalizedMd5)
                    .fileUrl(expectedTargetKey)
                    .status(TaskStatusEnum.MERGING.getCode())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .isDeleted(0)
                    .build();
            taskRecordMapper.insert(taskRecord);

            // 【调用 MinIO composeObject】在存储引擎内部将所有分片原子拼接为完整文件
            String mergedObjectKey = minioService.composeChunks(
                    normalizedMd5,
                    request.getTotalChunks(),
                    request.getFileName());

            // composeObject 成功后必须重新读取完整对象并计算 MD5，不能只相信客户端提交的指纹。
            String actualMd5 = minioService.calculateObjectMd5(mergedObjectKey);
            if (!normalizedMd5.equalsIgnoreCase(actualMd5)) {
                discardInvalidMergedObject(
                        taskRecord,
                        redisSetKey,
                        request.getTotalChunks(),
                        mergedObjectKey,
                        actualMd5);
            }

            // 完整 MD5 已验证后，才允许跨用户复用相同内容的成品对象。
            VideoTaskRecord sharedRecord = findCompletedContent(normalizedMd5, taskRecord.getId());
            String finalObjectKey = mergedObjectKey;
            if (sharedRecord != null) {
                finalObjectKey = sharedRecord.getFileUrl();
                if (!mergedObjectKey.equals(finalObjectKey)) {
                    try {
                        minioService.removeObject(mergedObjectKey);
                    } catch (RuntimeException cleanupError) {
                        log.warn("duplicate_merged_object_cleanup_failed objectKey={}",
                                mergedObjectKey, cleanupError);
                    }
                }
                log.info("upload_content_deduplicated fileMd5={} userId={} sourceTaskId={}",
                        normalizedMd5, userId, sharedRecord.getId());
            }

            // 【更新状态为已上传待分析(0)并清理现场】
            taskRecord.setFileUrl(finalObjectKey);
            taskRecord.setStatus(TaskStatusEnum.UPLOADED.getCode());
            taskRecord.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(taskRecord);

            redisTemplate.delete(redisSetKey);
            minioService.removeChunkObjects(normalizedMd5, request.getTotalChunks());

            return UploadMergeResponse.builder()
                    .taskId(taskRecord.getId())
                    .videoMd5(normalizedMd5)
                    .fileUrl(finalObjectKey)
                    .status(taskRecord.getStatus())
                    .build();
        } finally {
            if (mergeLock.isHeldByCurrentThread()) {
                mergeLock.unlock();
            }
        }
    }

    /**
     * 完整 MD5 对拍失败时，错误成品和分析任务都不能保留为可复用状态。
     * 同时清空分片进度，让用户重试时真正重新上传，而不是再次合并同一批错误分片。
     */
    private void discardInvalidMergedObject(VideoTaskRecord taskRecord,
                                            String redisSetKey,
                                            int totalChunks,
                                            String mergedObjectKey,
                                            String actualMd5) {
        String message = "完整文件内容校验失败，请重新上传";
        log.error("merged_file_md5_mismatch taskId={} fileMd5={} actualMd5={} objectKey={}",
                taskRecord.getId(), taskRecord.getVideoMd5(), actualMd5, mergedObjectKey);

        BusinessException mismatch = new BusinessException(ErrorCode.FILE_MD5_MISMATCH, message);
        try {
            minioService.removeObject(mergedObjectKey);
        } catch (RuntimeException cleanupError) {
            mismatch.addSuppressed(cleanupError);
            log.error("invalid_merged_object_cleanup_failed objectKey={}", mergedObjectKey, cleanupError);
        }
        try {
            redisTemplate.delete(redisSetKey);
        } catch (RuntimeException cleanupError) {
            mismatch.addSuppressed(cleanupError);
            log.error("invalid_merged_redis_cleanup_failed redisSetKey={}", redisSetKey, cleanupError);
        }
        minioService.removeChunkObjects(taskRecord.getVideoMd5(), totalChunks);
        try {
            taskRecordMapper.update(null, new UpdateWrapper<VideoTaskRecord>()
                    .eq("id", taskRecord.getId())
                    .set("file_url", null)
                    .set("status", TaskStatusEnum.FAILED.getCode())
                    .set("error_msg", message)
                    .set("update_time", LocalDateTime.now()));
        } catch (RuntimeException updateError) {
            mismatch.addSuppressed(updateError);
            log.error("invalid_merged_task_mark_failed_error taskId={}", taskRecord.getId(), updateError);
        }
        throw mismatch;
    }

    private VideoTaskRecord findCompletedContent(String videoMd5, Long excludeTaskId) {
        return taskRecordMapper.selectOne(
                new LambdaQueryWrapper<VideoTaskRecord>()
                        .eq(VideoTaskRecord::getVideoMd5, videoMd5)
                        .isNotNull(VideoTaskRecord::getFileUrl)
                        .in(VideoTaskRecord::getStatus,
                                TaskStatusEnum.UPLOADED.getCode(),
                                TaskStatusEnum.SUCCESS.getCode())
                        .ne(excludeTaskId != null, VideoTaskRecord::getId, excludeTaskId)
                        .orderByAsc(VideoTaskRecord::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 获取私有 Bucket 视频的 5 分钟有效预签名播放 URL
     */
    public String getPlayUrl(Long taskId, Long userId) {
        VideoTaskRecord record = taskRecordMapper.selectById(taskId);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频任务不存在");
        }
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人视频资源");
        }
        if (record.getFileUrl() == null || record.getFileUrl().isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "视频尚未合并完成");
        }
        return minioService.getPresignedPlayUrl(record.getFileUrl());
    }

    /**
     * 查询当前用户的历史视频任务列表（命中 idx_user_create 联合索引，天然按时间排序免 filesort）
     */
    public List<VideoTaskRecord> listUserTasks(Long userId) {
        return taskRecordMapper.selectList(
                new LambdaQueryWrapper<VideoTaskRecord>()
                        .eq(VideoTaskRecord::getUserId, userId)
                        .orderByDesc(VideoTaskRecord::getCreateTime));
    }

    public Set<Integer> getUploadedChunkIndexes(String fileMd5) {
        Set<String> members = redisTemplate.opsForSet().members(uploadSetKey(fileMd5));
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> indexes = new TreeSet<>();
        for (String member : members) {
            try {
                indexes.add(Integer.parseInt(member));
            } catch (NumberFormatException ignored) {
            }
        }
        return indexes;
    }

    public String uploadSetKey(String fileMd5) {
        return UPLOAD_SET_KEY_PREFIX + fileMd5;
    }

    public String normalizeMd5(String md5) {
        if (md5 == null || !md5.trim().matches("^[a-fA-F0-9]{32}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的32位MD5格式");
        }
        return md5.trim().toLowerCase(Locale.ROOT);
    }

    public String calculateMd5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }
}
