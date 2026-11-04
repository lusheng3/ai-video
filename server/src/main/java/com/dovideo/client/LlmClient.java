package com.dovideo.client;

import com.dovideo.client.dto.SummaryResult;

/**
 * 大模型结构化总结（LLM）客户端抽象。
 * 兼容任意 OpenAI Chat Completions 协议的服务（DeepSeek 官方 / 硅基流动 / 通义千问等）。
 */
public interface LlmClient {

    /**
     * 将逐字稿总结为结构化 JSON
     *
     * @param transcript 逐字稿文本
     * @param userGoal   用户分析目标（如“提炼核心考点”）
     * @return 结构化总结（summary / keyPoints / chapters）
     */
    SummaryResult summarize(String transcript, String userGoal);
}
