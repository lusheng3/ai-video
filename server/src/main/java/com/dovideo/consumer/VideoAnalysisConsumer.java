package com.dovideo.consumer;

import com.dovideo.client.AiClientException;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import com.dovideo.service.AnalysisResultWriter;
import com.dovideo.service.AnalysisService;
import com.dovideo.service.AudioExtractionService;
import com.dovideo.service.TaskEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 视频分析消费者 —— 严格遵循《AGENTS.md》标准六步流程：
 * ① 解析消息体（taskId / videoMd5 / minioPath / userGoal）
 * ② Redisson tryLock()（不指定 leaseTime，启用 WatchDog 每 10s 自动续期）
 * ③ 抢锁失败直接 return（ACK），说明已有其他消费者正在处理同一视频
 * ④ 抢锁成功后二次幂等校验（DB 已完成 / 同内容已有结果）
 * ⑤ 执行核心业务：MinIO 下载 -> FFmpeg 提取音频 -> ASR 转写 -> DeepSeek 结构化总结
 * ⑥ 落库（唯一索引 DuplicateKeyException 物理兜底）+ 更新状态 SUCCESS + finally 安全释放锁
 *
 * 业务异常一律向上抛出，交由 RocketMQ 阶梯延迟重投（maxReconsumeTimes = 3），
 * 3 次耗尽后进入死信队列，由 DeadLetterConsumer 统一置为 FAILED，前端停止轮询。
 */
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
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;

    @Override
    public void onMessage(AnalysisTaskMessage msg) {
        // ===== 步骤1：解析消息体并做结构性校验 =====
        if (msg == null || msg.getTaskId() == null || msg.getVideoMd5() == null || msg.getVideoMd5().isBlank()) {
            log.error("video_analysis_message_invalid payload={}", msg);
            return;
        }
        Long taskId = msg.getTaskId();
        String videoMd5 = msg.getVideoMd5();

        // ===== 步骤2：基于 videoMd5 争抢 Redisson 分布式锁（不传 leaseTime，启用 WatchDog） =====
        RLock lock = redissonClient.getLock("lock:video:" + videoMd5);
        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            // ===== 步骤3：抢锁失败直接 ACK，说明同一视频已有消费者在处理 =====
            if (!acquired) {
                log.info("video_analysis_skipped_lock_held taskId={} videoMd5={}", taskId, videoMd5);
                return;
            }

            VideoTaskRecord task = taskRecordMapper.selectById(taskId);
            if (task == null) {
                log.warn("video_analysis_task_not_found taskId={}", taskId);
                taskEventService.publishStage(taskId, AnalysisStageEnum.FAILED, "视频任务不存在", 0);
                return;
            }

            // ===== 步骤4：二次幂等校验 =====
            if (task.getStatus() != null && task.getStatus() == TaskStatusEnum.SUCCESS.getCode()) {
                log.info("video_analysis_already_success taskId={} videoMd5={}", taskId, videoMd5);
                taskEventService.publishStage(taskId, AnalysisStageEnum.COMPLETED, "分析已完成", 100);
                return;
            }
            if (hasExistingResult(videoMd5)) {
                // 同一内容的另一个任务已经分析完成：串行重复场景，直接复用结果，绝不再烧一次算力
                log.info("video_analysis_reuse_existing_result taskId={} videoMd5={}", taskId, videoMd5);
                resultWriter.markTaskSuccess(task);
                taskEventService.publishStage(
                        taskId, AnalysisStageEnum.REUSED, "命中已有分析结果，直接复用", 100);
                return;
            }

            // ===== 步骤5：执行核心业务逻辑 =====
            task.setStatus(TaskStatusEnum.PROCESSING.getCode());
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.CONSUMING, "消费者已接手，正在下载视频", 5);

            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.AUDIO_EXTRACTING, "正在下载视频并提取音频", 10);
            String transcript = audioExtractionService.transcribeFromMinio(
                    msg.getMinioPath(),
                    (stage, message, progress) ->
                            taskEventService.publishStage(taskId, stage, message, progress));

            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.LLM, "正在生成结构化总结", 78);
            SummaryResult summary = audioExtractionService.analyzeTranscript(transcript, msg.getUserGoal());

            // ===== 步骤6：唯一索引兜底落库 + 更新 SUCCESS =====
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.PERSISTING, "正在保存逐字稿与结构化结果", 92);
            resultWriter.persist(task, transcript, summary);
            taskEventService.publishStage(taskId, AnalysisStageEnum.COMPLETED, "分析完成", 100);
            log.info("video_analysis_completed taskId={} videoMd5={}", taskId, videoMd5);
        } catch (Exception ex) {
            // 永久性 AI 错误（401/402/403/400 等）：重投多少次都不会好，直接收敛为 FAILED，不再浪费 MQ 重试
            if (isPermanentAiFailure(ex)) {
                markPermanentFailure(taskId, videoMd5, ex.getMessage());
                log.error("video_analysis_permanent_failure taskId={} videoMd5={}", taskId, videoMd5, ex);
                return;
            }
            // 瞬时故障：标记 RETRYING 后向上抛出，交由 RocketMQ 阶梯延迟重投
            try {
                redisTemplate.opsForValue().set(
                        AnalysisService.STATUS_KEY_PREFIX + videoMd5,
                        AnalysisService.RETRYING,
                        AnalysisService.STATUS_TTL);
                taskEventService.publishStage(
                        taskId, AnalysisStageEnum.RETRYING,
                        "本次执行失败，正在等待消息队列自动重试", 0);
            } catch (RuntimeException redisEx) {
                log.warn("video_analysis_retry_status_write_failed videoMd5={}", videoMd5, redisEx);
            }
            log.error("video_analysis_consume_failed taskId={} videoMd5={}", taskId, videoMd5, ex);
            throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", ex);
        } finally {
            // ===== 铁律：检查当前线程是否持有锁，安全释放 =====
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean hasExistingResult(String videoMd5) {
        Long transcriptCount = transcriptionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.dovideo.entity.VideoTranscription>()
                        .eq(com.dovideo.entity.VideoTranscription::getVideoMd5, videoMd5));
        Long summaryCount = summaryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.dovideo.entity.AiSummaryResult>()
                        .eq(com.dovideo.entity.AiSummaryResult::getVideoMd5, videoMd5));
        return transcriptCount != null && transcriptCount > 0
                && summaryCount != null && summaryCount > 0;
    }

    /**
     * 沿 cause 链判断是否为「永久性 AI 错误」（429/5xx 之外的 4xx，例如 401 Key 错误、402 余额不足、403 无权限）。
     * 这类错误重投多少次都不会成功，必须立即收敛，避免浪费 MQ 重试次数。
     */
    private boolean isPermanentAiFailure(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof AiClientException aiEx && !aiEx.isRetryable()) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private void markPermanentFailure(Long taskId, String videoMd5, String reason) {
        String message = "AI 服务调用失败：" + (reason == null ? "未知错误" : reason);
        if (message.length() > 480) {
            message = message.substring(0, 480);
        }
        try {
            VideoTaskRecord task = taskRecordMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(TaskStatusEnum.FAILED.getCode());
                task.setErrorMsg(message);
                task.setUpdateTime(LocalDateTime.now());
                taskRecordMapper.updateById(task);
            }
            redisTemplate.opsForValue().set(
                    AnalysisService.STATUS_KEY_PREFIX + videoMd5,
                    AnalysisService.FAILED_PREFIX + message,
                    AnalysisService.STATUS_TTL);
            taskEventService.publishStage(
                    taskId, AnalysisStageEnum.FAILED, message, 0, message);
        } catch (RuntimeException cacheEx) {
            log.warn("video_analysis_permanent_failure_mark_failed_error taskId={}", taskId, cacheEx);
        }
    }
}
