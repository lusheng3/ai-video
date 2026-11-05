package com.dovideo.controller;

import com.dovideo.annotation.RateLimit;
import com.dovideo.common.Result;
import com.dovideo.dto.AnalysisStatusResponse;
import com.dovideo.dto.AnalysisSubmitRequest;
import com.dovideo.dto.AnalysisSubmitResponse;
import com.dovideo.service.AnalysisService;
import com.dovideo.service.AuthService;
import com.dovideo.service.TaskEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * AI 分析控制层：
 * - /submit 挂 @RateLimit 令牌桶限流，异步投递 MQ 后 50ms 内返回 taskId
 * - /status 供前端 3 秒轮询，同时作为 SSE 不可用时的降级方案
 * - /events/{taskId} 通过 SSE 实时推送阶段事件（Redis Pub/Sub 跨实例广播）
 * - /sync-benchmark 同步对照组（仅 Mock 模式），用于 JMeter 压测对比
 */
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final TaskEventService taskEventService;

    @RateLimit(rate = 1.0, capacity = 5)
    @PostMapping("/submit")
    public Result<AnalysisSubmitResponse> submit(
            @Valid @RequestBody AnalysisSubmitRequest request,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        return Result.ok(analysisService.submit(request.getTaskId(), request.getUserGoal(), userId));
    }

    @GetMapping("/status/{taskId}")
    public Result<AnalysisStatusResponse> status(
            @PathVariable Long taskId,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        return Result.ok(analysisService.getStatus(taskId, userId));
    }

    /**
     * SSE 实时事件流：注册连接后先补发一帧当前快照，随后推送消费者阶段事件；
     * 客户端断线重连后会再次拿到最新快照，不会因为错过中间事件而卡在旧进度。
     */
    @GetMapping(value = "/events/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable Long taskId,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        analysisService.requireOwnedTask(taskId, userId);
        return taskEventService.subscribe(taskId, () -> analysisService.eventSnapshot(taskId, userId));
    }

    @PostMapping("/sync-benchmark")
    public Result<Map<String, Object>> synchronousBenchmark(
            @RequestParam Long taskId,
            @RequestParam(defaultValue = "提炼视频核心内容") String userGoal,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        long elapsedMs = analysisService.runSynchronousBenchmark(taskId, userGoal, userId);
        return Result.ok(Map.of(
                "taskId", taskId,
                "elapsedMs", elapsedMs,
                "mode", "SYNC_BLOCKING"));
    }
}
