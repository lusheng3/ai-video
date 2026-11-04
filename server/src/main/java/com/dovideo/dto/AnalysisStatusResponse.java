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
    private Integer status;
    private String statusLabel;
    private String message;
    /** SUCCESS 时返回完整逐字稿 */
    private String transcription;
    /** SUCCESS 时返回结构化 AI 总结 */
    private SummaryResult summary;
    /** FAILED 时返回错误原因 */
    private String errorMsg;
}
