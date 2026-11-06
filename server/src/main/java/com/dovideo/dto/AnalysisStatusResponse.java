package com.dovideo.dto;

import com.dovideo.client.dto.SummaryResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisStatusResponse {

    private Long taskId;
    private String videoMd5;
    private String userGoal;
    private String goalDigest;
    private Integer status;
    private String statusLabel;
    private String message;
    /** 细粒度阶段（与 SSE 事件同源）：轮询降级时也能显示「正在转写第 i/N 段」 */
    private String stage;
    private String stageLabel;
    /** 0~100 粗粒度进度 */
    private Integer progress;
    /** SUCCESS 时返回完整逐字稿 */
    private String transcription;
    /** SUCCESS 时返回结构化 AI 总结 */
    private SummaryResult summary;
    /** FAILED 时返回错误原因 */
    private String errorMsg;
}
