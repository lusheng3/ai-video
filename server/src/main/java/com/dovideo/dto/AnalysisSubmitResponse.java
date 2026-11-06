package com.dovideo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisSubmitResponse {

    private Long taskId;
    private String videoMd5;
    private Integer status;
    private String statusLabel;
    private String goalDigest;
    /** ACCEPTED / REUSED / DUPLICATE */
    private String disposition;
    /** 是否直接复用了已有分析结果（内容级去重命中） */
    private boolean reused;
    private String message;
}
