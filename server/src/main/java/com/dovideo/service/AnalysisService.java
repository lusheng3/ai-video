package com.dovideo.service;

import com.alibaba.fastjson2.JSON;
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
import com.dovideo.util.AnalysisTaskKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final VideoTaskRecordMapper taskRecordMapper;
    private final VideoTranscriptionMapper transcriptionMapper;
    private final AiSummaryResultMapper summaryMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final AnalysisStateService stateService;
    private final AudioExtractionService audioExtractionService;
    private final AnalysisResultWriter resultWriter;
    private final AppProperties appProperties;
    private final TaskEventService taskEventService;
    private final AnalysisMetrics metrics;

    /**
     * API 入口使用 Redis SET NX 抢占当前上传任务的活动 Key。
     * 任务级 Key 避免一条上传记录同时承载多个进度；goalDigest 写入任务并在结果层隔离。
     */
    public AnalysisSubmitResponse submit(Long taskId, String userGoal, Long userId) {
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        String goal = AnalysisTaskKeys.normalizeGoal(userGoal);
        String goalDigest = AnalysisTaskKeys.goalDigest(goal);
        task.setUserGoal(goal);
        task.setGoalDigest(goalDigest);

        String activeGoalDigest = stateService.activeGoalDigest(taskId);
        if (activeGoalDigest != null && !goalDigest.equals(activeGoalDigest)) {
            metrics.submit("duplicate");
            return response(task, false, "该任务已有其他目标正在分析，请稍后重试",
                    TaskStatusEnum.QUEUED, "DUPLICATE");
        }

        if (hasExistingResult(task.getVideoMd5(), goalDigest)) {
            resultWriter.markTaskSuccess(task);
            resultWriter.cacheSuccess(task);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.REUSED, "命中同目标历史结果，已直接复用", 100);
            metrics.submit("reused");
            return response(task, true, "命中同目标历史结果，已直接复用",
                    TaskStatusEnum.SUCCESS, "REUSED");
        }

        if (!stateService.acquireActive(taskId, goalDigest)) {
            metrics.submit("duplicate");
            log.info("analysis_submit_duplicated taskId={} videoMd5={}", taskId, task.getVideoMd5());
            return response(task, false, "该任务正在分析中，请勿重复提交",
                    TaskStatusEnum.QUEUED, "DUPLICATE");
        }

        try {
            task.setStatus(TaskStatusEnum.QUEUED.getCode());
            task.setErrorMsg(null);
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
            stateService.markProcessing(taskId);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.QUEUED, "任务已进入异步分析队列", 0);

            AnalysisTaskMessage message = AnalysisTaskMessage.builder()
                    .taskId(taskId)
                    .userId(userId)
                    .videoMd5(task.getVideoMd5())
                    .minioPath(task.getFileUrl())
                    .userGoal(goal)
                    .goalDigest(goalDigest)
                    .build();
            SendResult sendResult = rocketMQTemplate.syncSend(
                    appProperties.getMq().getAnalysisTopic(), message);
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ 返回非 SEND_OK 状态");
            }
            metrics.submit("accepted");
            log.info("analysis_mq_sent taskId={} videoMd5={} msgId={}",
                    taskId, task.getVideoMd5(), sendResult.getMsgId());
            return response(task, false, "任务已进入异步分析队列",
                    TaskStatusEnum.QUEUED, "ACCEPTED");
        } catch (RuntimeException ex) {
            stateService.clearStatus(taskId);
            stateService.releaseActive(taskId);
            taskEventService.clearStage(taskId);
            task.setStatus(TaskStatusEnum.UPLOADED.getCode());
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
            metrics.mqSendFailure();
            metrics.submit("failed");
            log.error("analysis_mq_send_failed taskId={}", taskId, ex);
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，任务投递失败，请重试", ex);
        }
    }

    public AnalysisStatusResponse getStatus(Long taskId, Long userId) {
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        String cached = stateService.readStatus(taskId);
        AnalysisEvent stageSnapshot = taskEventService.readStage(taskId);

        if (cached != null && cached.startsWith(AnalysisStateService.FAILED_PREFIX)) {
            return baseStatus(task)
                    .status(TaskStatusEnum.FAILED.getCode())
                    .statusLabel("FAILED")
                    .stage(AnalysisStageEnum.FAILED.name())
                    .stageLabel(AnalysisStageEnum.FAILED.getDefaultMessage())
                    .progress(0)
                    .message(stageSnapshot == null ? "分析失败，请稍后重试" : stageSnapshot.getMessage())
                    .errorMsg(cached.substring(AnalysisStateService.FAILED_PREFIX.length()))
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
        if (stageSnapshot != null && stageSnapshot.getMessage() != null
                && !stageSnapshot.getMessage().isBlank()) {
            message = stageSnapshot.getMessage();
        }
        if (AnalysisStateService.RETRYING.equals(cached)) {
            message = "本次执行失败，正在等待消息队列自动重试";
        }
        return baseStatus(task)
                .status(status.getCode())
                .statusLabel(status.getRedisLabel())
                .message(message)
                .stage(stageSnapshot == null ? status.getRedisLabel() : stageSnapshot.getStage())
                .stageLabel(stageSnapshot == null ? message : stageSnapshot.getStageLabel())
                .progress(stageSnapshot == null ? 0 : stageSnapshot.getProgress())
                .errorMsg(task.getErrorMsg())
                .build();
    }

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

    public long runSynchronousBenchmark(Long taskId, String userGoal, Long userId) {
        if (!appProperties.isMockEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "同步对照组接口仅在 Mock 模式开放");
        }
        VideoTaskRecord task = requireOwnedTask(taskId, userId);
        String goal = AnalysisTaskKeys.normalizeGoal(userGoal);
        task.setUserGoal(goal);
        task.setGoalDigest(AnalysisTaskKeys.goalDigest(goal));
        long started = System.currentTimeMillis();
        task.setStatus(TaskStatusEnum.PROCESSING.getCode());
        taskRecordMapper.updateById(task);
        String transcript = audioExtractionService.transcribeFromMinio(task.getFileUrl());
        SummaryResult summary = audioExtractionService.analyzeTranscript(transcript, goal);
        resultWriter.persist(task, transcript, summary);
        resultWriter.cacheSuccess(task);
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

    public boolean hasExistingResult(String videoMd5, String goalDigest) {
        Long transcriptCount = transcriptionMapper.selectCount(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, videoMd5));
        Long summaryCount = summaryMapper.selectCount(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, videoMd5)
                        .eq(AiSummaryResult::getGoalDigest, goalDigest));
        return transcriptCount != null && transcriptCount > 0
                && summaryCount != null && summaryCount > 0;
    }

    private AnalysisStatusResponse buildSuccessResponse(VideoTaskRecord task) {
        VideoTranscription transcription = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, task.getVideoMd5())
                        .last("LIMIT 1"));
        AiSummaryResult summary = findSummary(task.getVideoMd5(), task.getGoalDigest());
        SummaryResult parsedSummary = null;
        if (summary != null && summary.getStructuredData() != null) {
            parsedSummary = JSON.parseObject(summary.getStructuredData(), SummaryResult.class);
        }
        return baseStatus(task)
                .status(TaskStatusEnum.SUCCESS.getCode())
                .statusLabel(AnalysisStateService.SUCCESS)
                .message("分析完成")
                .stage(AnalysisStageEnum.COMPLETED.name())
                .stageLabel(AnalysisStageEnum.COMPLETED.getDefaultMessage())
                .progress(100)
                .transcription(transcription == null ? null : transcription.getTranscriptionContent())
                .summary(parsedSummary)
                .build();
    }

    private AiSummaryResult findSummary(String videoMd5, String goalDigest) {
        if (goalDigest == null || goalDigest.isBlank()) {
            return null;
        }
        return summaryMapper.selectOne(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, videoMd5)
                        .eq(AiSummaryResult::getGoalDigest, goalDigest)
                        .last("LIMIT 1"));
    }

    private AnalysisStatusResponse.AnalysisStatusResponseBuilder baseStatus(VideoTaskRecord task) {
        return AnalysisStatusResponse.builder()
                .taskId(task.getId())
                .videoMd5(task.getVideoMd5())
                .userGoal(task.getUserGoal())
                .goalDigest(task.getGoalDigest());
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

    private AnalysisSubmitResponse response(VideoTaskRecord task,
                                            boolean reused,
                                            String message,
                                            TaskStatusEnum status,
                                            String disposition) {
        return AnalysisSubmitResponse.builder()
                .taskId(task.getId())
                .videoMd5(task.getVideoMd5())
                .goalDigest(task.getGoalDigest())
                .status(status.getCode())
                .statusLabel(status.getRedisLabel())
                .disposition(disposition)
                .reused(reused)
                .message(message)
                .build();
    }
}
