package com.dovideo.service;

import com.dovideo.dto.AnalysisEvent;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 分析任务事件中心（方案 B：SSE 实时推送 + Redis Pub/Sub 跨实例广播）。
 *
 * <p>为什么不是「消费者直接持有 SseEmitter」：
 * 消费者可能在 A 实例，用户浏览器连的是 B 实例，直接持有会推送不到。
 * 因此所有事件先经过 Redis Pub/Sub 广播，每个实例只把事件投递给「连在自己身上的订阅者」，
 * 这样水平扩容后仍然成立。
 *
 * <p>为什么还要写 Redis 快照：
 * 1. SSE 断线、刷新页面、切设备后，需要先补发一次当前状态，否则会丢事件；
 * 2. 轮询降级（GET /status）也要能读到同样的阶段文案，不能出现两套状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventService implements MessageListener {

    public static final String REDIS_CHANNEL = "dovideo:task-events";
    public static final String STAGE_KEY_PREFIX = "video:stage:";

    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final Duration STAGE_TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Subscription>> subscribers =
            new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 订阅任务事件。注册完成后才回调 initialEvent，避免「查询状态」与「建立连接」之间
     * 任务刚好完成而导致终态事件丢失。
     */
    public SseEmitter subscribe(Long taskId, Supplier<AnalysisEvent> initialEvent) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscription subscription = new Subscription(emitter);
        emitter.onCompletion(() -> remove(taskId, subscription));
        emitter.onTimeout(() -> remove(taskId, subscription));
        emitter.onError(error -> remove(taskId, subscription));

        subscribers.compute(taskId, (ignored, current) -> {
            CopyOnWriteArrayList<Subscription> list =
                    current == null ? new CopyOnWriteArrayList<>() : current;
            list.add(subscription);
            return list;
        });
        try {
            send(taskId, subscription, initialEvent.get(), true);
        } catch (RuntimeException ex) {
            remove(taskId, subscription);
            emitter.completeWithError(ex);
            throw ex;
        }
        return emitter;
    }

    /**
     * 发布阶段事件：先落 Redis 快照（供轮询降级 / 断线重连补发），再广播给所有实例。
     */
    public void publishStage(Long taskId,
                             AnalysisStageEnum stage,
                             String message,
                             Integer progress) {
        publishStage(taskId, stage, message, progress, null);
    }

    public void publishStage(Long taskId,
                             AnalysisStageEnum stage,
                             String message,
                             Integer progress,
                             String errorMsg) {
        if (taskId == null || stage == null) {
            return;
        }
        publish(AnalysisEvent.builder()
                .taskId(taskId)
                .status(stage.getStatus())
                .statusLabel(stage.getStatusLabel())
                .stage(stage.name())
                .stageLabel(stage.getDefaultMessage())
                .message(message == null || message.isBlank() ? stage.getDefaultMessage() : message)
                .progress(progress == null ? stage.getDefaultProgress() : progress)
                .errorMsg(errorMsg)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    public void publish(AnalysisEvent event) {
        if (event == null || event.getTaskId() == null) {
            return;
        }
        Long taskId = event.getTaskId();
        cacheStage(event);
        try {
            String payload = objectMapper.createObjectNode()
                    .put("taskId", taskId)
                    .set("event", objectMapper.valueToTree(event))
                    .toString();
            Long receivers = redisTemplate.convertAndSend(REDIS_CHANNEL, payload);
            // 没有任何订阅实例（例如 Redis Pub/Sub 未被容器订阅）时，退化为本机投递
            if (receivers == null || receivers == 0) {
                publishLocal(taskId, event);
            }
        } catch (RuntimeException ex) {
            log.warn("task_event_redis_publish_failed taskId={} stage={}", taskId, event.getStage(), ex);
            publishLocal(taskId, event);
        }
    }

    /** 供 SSE 初始快照与轮询降级读取；无记录时返回 null。 */
    public AnalysisEvent readStage(Long taskId) {
        try {
            String json = redisTemplate.opsForValue().get(STAGE_KEY_PREFIX + taskId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, AnalysisEvent.class);
        } catch (RuntimeException | IOException ex) {
            log.warn("task_stage_read_failed taskId={}", taskId, ex);
            return null;
        }
    }

    /** MQ 投递失败或任务回滚时清理阶段快照，避免前端看到过期进度。 */
    public void clearStage(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            redisTemplate.delete(STAGE_KEY_PREFIX + taskId);
        } catch (RuntimeException ex) {
            log.warn("task_stage_clear_failed taskId={}", taskId, ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = objectMapper.readTree(message.getBody());
            long taskId = payload.path("taskId").asLong();
            AnalysisEvent event = objectMapper.treeToValue(payload.path("event"), AnalysisEvent.class);
            publishLocal(taskId, event);
        } catch (Exception ex) {
            log.warn("task_event_redis_message_invalid", ex);
        }
    }

    private void cacheStage(AnalysisEvent event) {
        try {
            redisTemplate.opsForValue().set(
                    STAGE_KEY_PREFIX + event.getTaskId(),
                    objectMapper.writeValueAsString(event),
                    STAGE_TTL);
        } catch (RuntimeException | IOException ex) {
            log.warn("task_stage_cache_failed taskId={}", event.getTaskId(), ex);
        }
    }

    private void publishLocal(Long taskId, AnalysisEvent event) {
        List<Subscription> subscriptions = subscribers.get(taskId);
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }
        subscriptions.forEach(subscription -> send(taskId, subscription, event, false));
    }

    private void send(Long taskId,
                      Subscription subscription,
                      AnalysisEvent event,
                      boolean initial) {
        synchronized (subscription) {
            if (subscription.closed || (initial && subscription.liveEventDelivered)) {
                return;
            }
            if (!initial) {
                subscription.liveEventDelivered = true;
            }
            try {
                subscription.emitter.send(SseEmitter.event().name("task-status").data(event));
                if (event.terminal()) {
                    remove(taskId, subscription);
                    subscription.emitter.complete();
                }
            } catch (IOException | RuntimeException ex) {
                remove(taskId, subscription);
                try {
                    subscription.emitter.completeWithError(ex);
                } catch (RuntimeException ignored) {
                    // 连接已经被容器关闭，无需重复处理
                }
                log.debug("task_event_stream_closed taskId={} stage={}", taskId, event.getStage());
            }
        }
    }

    private void remove(Long taskId, Subscription subscription) {
        synchronized (subscription) {
            subscription.closed = true;
            subscribers.computeIfPresent(taskId, (ignored, list) -> {
                list.remove(subscription);
                return list.isEmpty() ? null : list;
            });
        }
    }

    private static final class Subscription {
        private final SseEmitter emitter;
        private boolean liveEventDelivered;
        private boolean closed;

        private Subscription(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    /** 给日志/测试使用的状态标签，避免重复写枚举转换。 */
    static String statusLabelOf(int status) {
        return TaskStatusEnum.fromCode(status).getRedisLabel();
    }
}
