package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.AnalysisEvent;
import com.dovideo.dto.AnalysisStatusResponse;
import com.dovideo.dto.AnalysisSubmitResponse;
import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.AiSummaryResult;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.entity.VideoTranscription;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * AI 分析编排服务（对应简历亮点1）：
 * 1. 令牌桶限流（由 @RateLimit 切面在 Controller 层完成）
 * 2. 锁外前置状态拦截：Redis 状态 -> MySQL 历史结果复用
 * 3. 落库标记排队中 + 写 Redis PROCESSING
 * 4. 同步发送轻量消息到 RocketMQ 专属 Topic，收到 SEND_OK 后 50ms 内返回 taskId
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    public static final String STATUS_KEY_PREFIX = "video:status:";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String RETRYING = "RETRYING";
    public static final String FAILED_PREFIX = "FAILED:";
    public static final Duration STATUS_TTL = Duration.ofHours(1);

    private final VideoTaskRecordMapper taskRecordMapper;
    private final VideoTranscriptionMapper transcriptionMapper;
    private final AiSummaryResultMapper summaryMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final AudioExtractionService audioExtractionService;
    private final AnalysisResultWriter resultWriter;
    private final AppProperties appProperties;
    private final TaskEventService taskEventService;

    /**
     * 提交 AI 分析任务：锁外前置拦截 -> 标记排队 -> 同步发送 MQ -> 立即返回
     */
    public AnalysisSubmitResponse submit(Long taskId, String userGoal, Long userId) {
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        if (task.getStatus() != null && task.getStatus() == TaskStatusEnum.SUCCESS.getCode()) {
            return response(task, true, "该视频分析已完成，直接返回结果");
        }

        String statusKey = STATUS_KEY_PREFIX + task.getVideoMd5();
        String cached = redisTemplate.opsForValue().get(statusKey);
        if (cached != null && cached.startsWith(PROCESSING)) {
            log.info("analysis_submit_duplicated_in_flight taskId={} videoMd5={}", taskId, task.getVideoMd5());
            return response(task, false, "该视频正在分析中，请勿重复提交");
        }

        // 锁外第一层：MySQL 中已有同内容的分析结果，直接复用，零算力消耗
        if (hasExistingResult(task.getVideoMd5())) {
            resultWriter.markTaskSuccess(task);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.REUSED, "命中内容级去重，已复用历史分析结果", 100);
            log.info("analysis_submit_reused_by_md5 taskId={} videoMd5={}", taskId, task.getVideoMd5());
            return response(task, true, "命中内容级去重，已复用历史分析结果");
        }

        task.setStatus(TaskStatusEnum.QUEUED.getCode());
        task.setUpdateTime(LocalDateTime.now());
        taskRecordMapper.updateById(task);
        redisTemplate.opsForValue().set(statusKey, PROCESSING, STATUS_TTL);
        // 先发排队事件再投递：消费者可能立刻接手，若顺序反了会让前端进度从「已接手」回退到「排队中」
        taskEventService.publishStage(
                taskId, AnalysisStageEnum.QUEUED, "任务已进入异步分析队列", 0);

        AnalysisTaskMessage message = AnalysisTaskMessage.builder()
                .taskId(task.getId())
                .userId(userId)
                .videoMd5(task.getVideoMd5())
                .minioPath(task.getFileUrl())
                .userGoal(userGoal)
                .build();
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(
                    appProperties.getMq().getAnalysisTopic(), message);
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ 返回非 SEND_OK 状态");
            }
            log.info("analysis_mq_sent taskId={} videoMd5={} msgId={}",
                    taskId, task.getVideoMd5(), sendResult.getMsgId());
        } catch (RuntimeException ex) {
            // MQ 投递失败必须回滚状态，并清理阶段快照，否则前端会看到一条不存在的排队进度
            redisTemplate.delete(statusKey);
            taskEventService.clearStage(taskId);
            task.setStatus(TaskStatusEnum.UPLOADED.getCode());
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
            log.error("analysis_mq_send_failed taskId={}", taskId, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，任务投递失败，请重试", ex);
        }

        return response(task, false, "任务已进入异步分析队列");
    }

    /**
     * 3 秒轮询状态查询：优先读 Redis 热状态，SUCCESS 时联表返回完整逐字稿与结构化总结
     */
    public AnalysisStatusResponse getStatus(Long taskId, Long userId) {
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        String statusKey = STATUS_KEY_PREFIX + task.getVideoMd5();
        String cached = redisTemplate.opsForValue().get(statusKey);
        AnalysisEvent stageSnapshot = taskEventService.readStage(taskId);

        if (cached != null && cached.startsWith(FAILED_PREFIX)) {
            return AnalysisStatusResponse.builder()
                    .taskId(taskId).videoMd5(task.getVideoMd5())
                    .status(TaskStatusEnum.FAILED.getCode()).statusLabel("FAILED")
                    .stage(AnalysisStageEnum.FAILED.name())
                    .stageLabel(AnalysisStageEnum.FAILED.getDefaultMessage())
                    .progress(0)
                    .message(stageSnapshot == null ? "分析失败，请稍后重试" : stageSnapshot.getMessage())
                    .errorMsg(cached.substring(FAILED_PREFIX.length()))
                    .build();
        }

        if (task.getStatus() != null && task.getStatus() == TaskStatusEnum.SUCCESS.getCode()) {
            return buildSuccessResponse(task);
        }

        TaskStatusEnum status = TaskStatusEnum.fromCode(task.getStatus());
        String message = switch (status) {
            case QUEUED -> "任务已排队，等待消费者处理";
            case PROCESSING -> "正在提取音频并调用 AI 分析";
            case MERGING -> "视频合并中";
            case FAILED -> "分析失败，请稍后重试";
            default -> "任务已提交";
        };
        if (stageSnapshot != null && stageSnapshot.getMessage() != null && !stageSnapshot.getMessage().isBlank()) {
            message = stageSnapshot.getMessage();
        }
        if (RETRYING.equals(cached)) {
            message = "本次执行失败，正在等待消息队列自动重试";
        }
        return AnalysisStatusResponse.builder()
                .taskId(taskId).videoMd5(task.getVideoMd5())
                .status(status.getCode()).statusLabel(status.getRedisLabel())
                .message(message)
                .stage(stageSnapshot == null ? status.getRedisLabel() : stageSnapshot.getStage())
                .stageLabel(stageSnapshot == null ? message : stageSnapshot.getStageLabel())
                .progress(stageSnapshot == null ? 0 : stageSnapshot.getProgress())
                .errorMsg(task.getErrorMsg())
                .build();
    }

    /**
     * SSE 建立连接后的第一帧快照：只读轻量状态，不查询逐字稿/总结大字段。
     * 已完成任务直接返回终态，让客户端收到后立刻关闭连接并另行拉取完整结果。
     */
    public AnalysisEvent eventSnapshot(Long taskId, Long userId) {
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        TaskStatusEnum status = TaskStatusEnum.fromCode(task.getStatus());
        if (status == TaskStatusEnum.SUCCESS) {
            return terminalEvent(taskId, AnalysisStageEnum.COMPLETED, "分析完成");
        }
        if (status == TaskStatusEnum.FAILED) {
            return terminalEvent(taskId, AnalysisStageEnum.FAILED,
                    task.getErrorMsg() == null ? "分析失败，请稍后重试" : task.getErrorMsg());
        }
        AnalysisEvent snapshot = taskEventService.readStage(taskId);
        if (snapshot != null) {
            return snapshot;
        }
        // 尚未提交分析的已上传任务不能谎报「消费者已接手」，要如实告诉前端还需用户点分析
        AnalysisStageEnum stage = status == TaskStatusEnum.QUEUED
                ? AnalysisStageEnum.QUEUED : AnalysisStageEnum.CONSUMING;
        String message = status == TaskStatusEnum.UPLOADED
                ? "视频已上传，等待提交分析" : stage.getDefaultMessage();
        return AnalysisEvent.builder()
                .taskId(taskId)
                .status(status.getCode())
                .statusLabel(status.getRedisLabel())
                .stage(stage.name())
                .stageLabel(message)
                .message(message)
                .progress(status == TaskStatusEnum.UPLOADED ? 0 : stage.getDefaultProgress())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private AnalysisEvent terminalEvent(Long taskId, AnalysisStageEnum stage, String message) {
        return AnalysisEvent.builder()
                .taskId(taskId)
                .status(stage.getStatus())
                .statusLabel(stage.getStatusLabel())
                .stage(stage.name())
                .stageLabel(stage.getDefaultMessage())
                .message(message)
                .progress(stage.getDefaultProgress())
                .errorMsg(stage == AnalysisStageEnum.FAILED ? message : null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 同步阻塞对照组：用于 JMeter 对比验证「同步 60s+ 阻塞」与「异步 50ms 返回」
     * 仅在 Mock 模式下开放，避免误调用烧真实 Token。
     */
    public long runSynchronousBenchmark(Long taskId, String userGoal, Long userId) {
        if (!appProperties.isMockEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "同步对照组接口仅在 Mock 模式开放");
        }
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        long started = System.currentTimeMillis();

        task.setStatus(TaskStatusEnum.PROCESSING.getCode());
        taskRecordMapper.updateById(task);

        String transcript = audioExtractionService.transcribeFromMinio(task.getFileUrl());
        SummaryResult summary = audioExtractionService.analyzeTranscript(transcript, userGoal);
        resultWriter.persist(task, transcript, summary);
        return System.currentTimeMillis() - started;
    }

    public VideoTaskRecord requireOwnedTask(Long taskId, Long userId) {
        VideoTaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作他人视频任务");
        }
        return task;
    }

    public boolean hasExistingResult(String videoMd5) {
        Long transcriptCount = transcriptionMapper.selectCount(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, videoMd5));
        Long summaryCount = summaryMapper.selectCount(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, videoMd5));
        return transcriptCount != null && transcriptCount > 0
                && summaryCount != null && summaryCount > 0;
    }

    private AnalysisStatusResponse buildSuccessResponse(VideoTaskRecord task) {
        VideoTranscription transcription = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, task.getVideoMd5())
                        .last("LIMIT 1"));
        AiSummaryResult summary = summaryMapper.selectOne(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, task.getVideoMd5())
                        .last("LIMIT 1"));
        SummaryResult parsedSummary = null;
        if (summary != null && summary.getStructuredData() != null) {
            parsedSummary = com.alibaba.fastjson2.JSON.parseObject(
                    summary.getStructuredData(), SummaryResult.class);
        }
        return AnalysisStatusResponse.builder()
                .taskId(task.getId())
                .videoMd5(task.getVideoMd5())
                .status(TaskStatusEnum.SUCCESS.getCode())
                .statusLabel(SUCCESS)
                .message("分析完成")
                .stage(AnalysisStageEnum.COMPLETED.name())
                .stageLabel(AnalysisStageEnum.COMPLETED.getDefaultMessage())
                .progress(100)
                .transcription(transcription == null ? null : transcription.getTranscriptionContent())
                .summary(parsedSummary)
                .build();
    }

    private AnalysisSubmitResponse response(VideoTaskRecord task, boolean reused, String message) {
        TaskStatusEnum status = TaskStatusEnum.fromCode(task.getStatus());
        return AnalysisSubmitResponse.builder()
                .taskId(task.getId())
                .videoMd5(task.getVideoMd5())
                .status(status.getCode())
                .statusLabel(status.getRedisLabel())
                .reused(reused)
                .message(message)
                .build();
    }
}
