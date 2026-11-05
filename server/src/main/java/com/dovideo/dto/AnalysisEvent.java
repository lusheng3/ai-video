package com.dovideo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分析任务事件：SSE 实时推送与 3 秒轮询降级共用同一个载荷，
 * 保证「推送」和「查询」两条链路看到的状态语义完全一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisEvent {

    private Long taskId;
    /** 任务主状态码：1-排队中，2-处理中，3-已完成，4-失败 */
    private Integer status;
    private String statusLabel;
    /** 细粒度阶段：QUEUED / AUDIO_EXTRACTING / ASR / LLM / PERSISTING / COMPLETED ... */
    private String stage;
    private String stageLabel;
    /** 给用户看的中文进度文案 */
    private String message;
    /** 0~100 的粗粒度进度，前端用于进度条 */
    private Integer progress;
    private String errorMsg;
    private Long timestamp;

    /** 终态事件推完即关闭 SSE 连接，避免长连接空挂。 */
    public boolean terminal() {
        return status != null && (status == 3 || status == 4);
    }
}
