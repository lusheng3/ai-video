package com.dovideo.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.client.AiClientException;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.AiSummaryResult;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.entity.VideoTranscription;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import com.dovideo.service.AnalysisMetrics;
import com.dovideo.service.AnalysisResultWriter;
import com.dovideo.service.AnalysisStateService;
import com.dovideo.service.AudioExtractionService;
import com.dovideo.service.TaskEventService;
import com.dovideo.util.AnalysisTaskKeys;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${app.mq.analysis-topic}",
        consumerGroup = "${app.mq.consumer-group}",
        maxReconsumeTimes = 3)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMessage> {

    private final RedissonClient redissonClient;
    private final VideoTaskRecordMapper taskRecordMapper;
    private final VideoTranscriptionMapper transcriptionMapper;
    private final AiSummaryResultMapper summaryMapper;
    private final AudioExtractionService audioExtractionService;
    private final AnalysisResultWriter resultWriter;
    private final TaskEventService taskEventService;
    private final AnalysisStateService stateService;
    private final AnalysisMetrics metrics;
    private final AppProperties appProperties;

    @Override
    public void onMessage(AnalysisTaskMessage msg) {
        if (!validMessage(msg)) {
            return;
        }
        Long taskId = msg.getTaskId();
        String videoMd5 = msg.getVideoMd5();
        String goal = AnalysisTaskKeys.normalizeGoal(msg.getUserGoal());
        String goalDigest = AnalysisTaskKeys.goalDigest(goal);

        RLock analysisLock = redissonClient.getLock(
                AnalysisTaskKeys.analysisLock(videoMd5, goalDigest));
        RLock contentLock = redissonClient.getLock(AnalysisTaskKeys.contentLock(videoMd5));
        boolean analysisAcquired = false;
        boolean contentAcquired = false;
        boolean retrying = false;
        String metricResult = "failed";
        Timer.Sample processingTimer = metrics.startTimer();
        try {
            long lockStarted = System.currentTimeMillis();
            analysisAcquired = analysisLock.tryLock(
                    appProperties.getAnalysis().getLockWaitSeconds(), TimeUnit.SECONDS);
            metrics.recordWait("analysis", System.currentTimeMillis() - lockStarted);
            if (!analysisAcquired) {
                metrics.lock("wait_failed");
                if (reuseAvailableResult(taskId, goal, goalDigest)) {
                    metricResult = "reused";
                    return;
                }
                throw new IllegalStateException("同目标分析锁等待超时，交由消息队列重试");
            }
            metrics.lock("acquired");

            VideoTaskRecord task = taskRecordMapper.selectById(taskId);
            if (task == null) {
                log.warn("video_analysis_task_not_found taskId={}", taskId);
                return;
            }
            if (task.getGoalDigest() != null && !goalDigest.equals(task.getGoalDigest())) {
                log.info("video_analysis_stale_goal_discarded taskId={} messageGoal={} currentGoal={}",
                        taskId, goalDigest, task.getGoalDigest());
                return;
            }
            task.setUserGoal(goal);
            task.setGoalDigest(goalDigest);

            if (reuseAvailableResult(taskId, goal, goalDigest)) {
                metricResult = "reused";
                return;
            }

            long contentLockStarted = System.currentTimeMillis();
            contentAcquired = contentLock.tryLock(
                    appProperties.getAnalysis().getLockWaitSeconds(), TimeUnit.SECONDS);
            metrics.recordWait("content", System.currentTimeMillis() - contentLockStarted);
            if (!contentAcquired) {
                metrics.lock("content_wait_failed");
                if (findTranscription(videoMd5) == null) {
                    throw new IllegalStateException("视频内容锁等待超时，交由消息队列重试");
                }
            }

            task.setStatus(TaskStatusEnum.PROCESSING.getCode());
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
            stateService.markProcessing(taskId);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.CONSUMING, "消费者已接手，正在准备视频解析", 5);

            VideoTranscription existingTranscription = findTranscription(videoMd5);
            String transcript = existingTranscription == null
                    ? null : existingTranscription.getTranscriptionContent();
            if (transcript == null) {
                taskEventService.publishStage(
                        taskId, AnalysisStageEnum.AUDIO_EXTRACTING,
                        "正在下载视频并提取音频", 10);
                transcript = audioExtractionService.transcribeFromMinio(
                        msg.getMinioPath(),
                        (stage, message, progress) ->
                                taskEventService.publishStage(taskId, stage, message, progress));
                resultWriter.saveTranscriptionIfAbsent(task, transcript);
            } else {
                taskEventService.publishStage(
                        taskId, AnalysisStageEnum.ASR, "复用同内容逐字稿，跳过 ASR", 70);
            }

            if (contentAcquired) {
                contentLock.unlock();
                contentAcquired = false;
            }

            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.LLM, "正在生成目标对应的结构化总结", 78);
            SummaryResult summary = audioExtractionService.analyzeTranscript(transcript, goal);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.PERSISTING, "正在保存分析结果", 92);
            resultWriter.persist(task, transcript, summary);
            resultWriter.cacheSuccess(task);
            metricResult = "completed";
            metrics.result("completed");
            taskEventService.publishStage(taskId, AnalysisStageEnum.COMPLETED, "分析完成", 100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            retrying = true;
            stateService.markRetrying(taskId);
            stateService.refreshActive(taskId);
            throw new IllegalStateException("等待分布式锁时被中断", ex);
        } catch (Exception ex) {
            if (isPermanentAiFailure(ex)) {
                markPermanentFailure(taskId, videoMd5, goal, goalDigest, ex.getMessage());
                metricResult = "failed";
                metrics.result("failed");
                log.error("video_analysis_permanent_failure taskId={} videoMd5={}", taskId, videoMd5, ex);
                return;
            }
            retrying = true;
            stateService.markRetrying(taskId);
            stateService.refreshActive(taskId);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.RETRYING,
                    "本次执行失败，正在等待消息队列自动重试", 0);
            metrics.result("retry");
            log.error("video_analysis_consume_failed taskId={} videoMd5={}", taskId, videoMd5, ex);
            throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", ex);
        } finally {
            if (!retrying) {
                releaseOwnedActive(taskId, goalDigest);
            }
            metrics.stopProcessingTimer(processingTimer, metricResult);
            if (contentAcquired && contentLock.isHeldByCurrentThread()) {
                contentLock.unlock();
            }
            if (analysisAcquired && analysisLock.isHeldByCurrentThread()) {
                analysisLock.unlock();
            }
        }
    }

    private boolean validMessage(AnalysisTaskMessage msg) {
        if (msg == null || msg.getTaskId() == null || msg.getVideoMd5() == null
                || msg.getVideoMd5().isBlank() || msg.getUserGoal() == null
                || msg.getUserGoal().isBlank()) {
            log.error("video_analysis_message_invalid payload={}", msg);
            return false;
        }
        return true;
    }

    private boolean reuseAvailableResult(Long taskId, String goal, String goalDigest) {
        VideoTaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        if (task.getGoalDigest() != null && !goalDigest.equals(task.getGoalDigest())) {
            return false;
        }
        AiSummaryResult summary = findSummary(task.getVideoMd5(), goalDigest);
        if (summary == null) {
            return false;
        }
        task.setUserGoal(goal);
        task.setGoalDigest(goalDigest);
        resultWriter.markTaskSuccess(task);
        resultWriter.cacheSuccess(task);
        metrics.result("reused");
        taskEventService.publishStage(
                taskId, AnalysisStageEnum.REUSED, "命中同视频同目标结果，直接复用", 100);
        return true;
    }

    private VideoTranscription findTranscription(String videoMd5) {
        return transcriptionMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoMd5, videoMd5)
                        .last("LIMIT 1"));
    }

    private AiSummaryResult findSummary(String videoMd5, String goalDigest) {
        return summaryMapper.selectOne(
                new LambdaQueryWrapper<AiSummaryResult>()
                        .eq(AiSummaryResult::getVideoMd5, videoMd5)
                        .eq(AiSummaryResult::getGoalDigest, goalDigest)
                        .last("LIMIT 1"));
    }

    private boolean isPermanentAiFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof AiClientException aiError && !aiError.isRetryable()) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return error instanceof IllegalArgumentException;
    }

    private void markPermanentFailure(Long taskId,
                                      String videoMd5,
                                      String goal,
                                      String goalDigest,
                                      String reason) {
        String message = "AI 服务调用失败：" + (reason == null ? "未知错误" : reason);
        if (message.length() > 480) {
            message = message.substring(0, 480);
        }
        try {
            taskRecordMapper.update(null, new UpdateWrapper<VideoTaskRecord>()
                    .eq("id", taskId)
                    .set("status", TaskStatusEnum.FAILED.getCode())
                    .set("user_goal", goal)
                    .set("goal_digest", goalDigest)
                    .set("error_msg", message)
                    .set("update_time", LocalDateTime.now()));
            stateService.markFailed(taskId, message);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.FAILED, message, 0, message);
        } catch (RuntimeException ex) {
            log.warn("video_analysis_permanent_failure_mark_failed_error taskId={}", taskId, ex);
        }
    }

    private void releaseOwnedActive(Long taskId, String goalDigest) {
        String activeGoal = stateService.activeGoalDigest(taskId);
        if (activeGoal == null || goalDigest.equals(activeGoal)) {
            stateService.releaseActive(taskId);
        }
    }
}
