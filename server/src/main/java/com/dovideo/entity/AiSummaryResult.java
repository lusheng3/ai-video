package com.dovideo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表4：AI分析结果表实体 (ai_summary_result) —— 垂直分表
 * 独立存放大模型返回的 JSON 结构化数据（时间轴章节、核心要点、摘要），免去频繁 DDL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_summary_result")
public class AiSummaryResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String videoMd5;

    /** JSON字符串：{"summary": "...", "keyPoints": [...], "chapters": [...]} */
    private String structuredData;

    private Integer tokenCost;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
