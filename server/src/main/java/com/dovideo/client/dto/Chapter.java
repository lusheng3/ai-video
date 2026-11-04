package com.dovideo.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 总结中的章节时间轴（用于前端点击跳转视频对应位置）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chapter {

    private String title;

    /** 章节起始时间，如 "00:00" */
    private String startTime;

    /** 章节结束时间，如 "02:30" */
    private String endTime;

    private String content;
}
