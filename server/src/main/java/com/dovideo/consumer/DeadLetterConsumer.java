package com.dovideo.consumer;

import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.service.AnalysisService;
import com.dovideo.service.TaskEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 死信队列消费者（DLQ 兜底）：
 * 当主消费者 3 次 MQ 重投全部耗尽后，消息进入 %DLQ%video-analysis-group。
 * 该监听器负责将 MySQL 与 Redis 状态置为 FAILED，并记录错误原因，
 * 确保前端 3 秒轮询能立刻感知失败并停止转圈，而不是永远等待。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${app.mq.dlq-topic}",
        consumerGroup = "video-analysis-dlq-group")
public class DeadLetterConsumer implements RocketMQListener<AnalysisTaskMessage> {

    private final VideoTaskRecordMapper taskRecordMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;

    @Override
    public void onMessage(AnalysisTaskMessage msg) {
        if (msg == null || msg.getTaskId() == null) {
            log.error("dead_letter_message_invalid payload={}", msg);
            return;
        }
        String errorMsg = "分析重试次数已耗尽，任务进入死信队列，请联系管理员处理";
        VideoTaskRecord task = taskRecordMapper.selectById(msg.getTaskId());
        if (task != null) {
            task.setStatus(TaskStatusEnum.FAILED.getCode());
            task.setErrorMsg(errorMsg);
            task.setUpdateTime(LocalDateTime.now());
            taskRecordMapper.updateById(task);
        }
        if (msg.getVideoMd5() != null) {
            redisTemplate.opsForValue().set(
                    AnalysisService.STATUS_KEY_PREFIX + msg.getVideoMd5(),
                    AnalysisService.FAILED_PREFIX + errorMsg,
                    AnalysisService.STATUS_TTL);
        }
        log.error("dead_letter_task_marked_failed taskId={} videoMd5={}", msg.getTaskId(), msg.getVideoMd5());
        taskEventService.publishStage(
                msg.getTaskId(), AnalysisStageEnum.FAILED, errorMsg, 0, errorMsg);
    }
}
