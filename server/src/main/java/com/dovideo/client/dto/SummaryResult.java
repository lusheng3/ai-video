package com.dovideo.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 输出的结构化总结（写入 ai_summary_result.structured_data 的 JSON）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResult {

    /** 核心摘要 */
    private String summary;

    /** 核心要点 */
    @Builder.Default
    private List<String> keyPoints = new ArrayList<>();

    /** 章节时间轴 */
    @Builder.Default
    private List<Chapter> chapters = new ArrayList<>();

    /** 本次调用消耗的 token 总数 */
    private Integer tokenCost;
}
