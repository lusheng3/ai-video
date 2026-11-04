package com.dovideo.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.entity.AiSummaryResult;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.entity.VideoTranscription;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 分析结果落库器：
 * 1. 逐字稿写入 video_transcription（video_md5 唯一索引，捕获 DuplicateKeyException 幂等容忍）
 * 2. 结构化总结写入 ai_summary_result（video_md5 唯一索引物理防重兜底）
 * 3. 主表状态更新为 SUCCESS，并同步 Redis 状态缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultWriter {

    public static final String STATUS_KEY_PREFIX = "video:status:";
    public static final String SUCCESS = "SUCCESS";
    public static final Duration STATUS_TTL = Duration.ofHours(1);

    private final VideoTranscriptionMapper transcriptionMapper;
    private final AiSummaryResultMapper summaryMapper;
    private final VideoTaskRecordMapper taskRecordMapper;
    private final StringRedisTemplate redisTemplate;

    public void persist(VideoTaskRecord task, String transcript, SummaryResult summary) {
        saveTranscription(task, transcript);
        saveSummary(task, summary);
        markTaskSuccess(task);
    }

    public void markTaskSuccess(VideoTaskRecord task) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(TaskStatusEnum.SUCCESS.getCode());
        task.setUpdateTime(now);
        task.setErrorMsg(null);
        // MyBatis-Plus updateById 默认忽略 null 字段，无法把历史失败留下的 error_msg 清空，
        // 因此这里用 LambdaUpdateWrapper 显式 set(error_msg, null)，保证成功后不再显示旧错误。
        taskRecordMapper.update(null, new LambdaUpdateWrapper<VideoTaskRecord>()
                .eq(VideoTaskRecord::getId, task.getId())
                .set(VideoTaskRecord::getStatus, TaskStatusEnum.SUCCESS.getCode())
                .set(VideoTaskRecord::getErrorMsg, null)
                .set(VideoTaskRecord::getUpdateTime, now));
        redisTemplate.opsForValue().set(
                STATUS_KEY_PREFIX + task.getVideoMd5(), SUCCESS, STATUS_TTL);
    }

    private void saveTranscription(VideoTaskRecord task, String transcript) {
        VideoTranscription existing = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, task.getVideoMd5())
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("transcription_already_exists videoMd5={} taskId={}", task.getVideoMd5(), task.getId());
            return;
        }
        try {
            transcriptionMapper.insert(VideoTranscription.builder()
                    .taskId(task.getId())
                    .videoMd5(task.getVideoMd5())
                    .transcriptionContent(transcript)
                    .wordCount(transcript == null ? 0 : transcript.length())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .isDeleted(0)
                    .build());
        } catch (DuplicateKeyException ex) {
            log.warn("transcription_duplicate_key_ignored videoMd5={}", task.getVideoMd5());
        }
    }

    private void saveSummary(VideoTaskRecord task, SummaryResult summary) {
        AiSummaryResult existing = summaryMapper.selectOne(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, task.getVideoMd5())
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("summary_already_exists videoMd5={} taskId={}", task.getVideoMd5(), task.getId());
            return;
        }
        try {
            summaryMapper.insert(AiSummaryResult.builder()
                    .taskId(task.getId())
                    .videoMd5(task.getVideoMd5())
                    .structuredData(JSON.toJSONString(summary))
                    .tokenCost(summary.getTokenCost() == null ? 0 : summary.getTokenCost())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .isDeleted(0)
                    .build());
        } catch (DuplicateKeyException ex) {
            log.warn("summary_duplicate_key_ignored videoMd5={}", task.getVideoMd5());
        }
    }
}
