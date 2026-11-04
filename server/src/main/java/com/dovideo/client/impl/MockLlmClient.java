package com.dovideo.client.impl;

import com.dovideo.client.LlmClient;
import com.dovideo.client.dto.Chapter;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地压测 Mock LLM：按可配置延迟返回标准结构化总结，不产生任何 Token 费用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private final AppProperties appProperties;

    @Override
    public SummaryResult summarize(String transcript, String userGoal) {
        sleepQuietly(appProperties.getMockProcessDelayMs());
        log.info("mock_llm_summarize transcriptChars={} goal={}",
                transcript == null ? 0 : transcript.length(), userGoal);
        return SummaryResult.builder()
                .summary("本视频系统讲解了二叉树前序遍历的定义、递归实现与复杂度分析，适合作为数据结构入门复习材料。")
                .keyPoints(List.of(
                        "前序遍历顺序为「根节点 → 左子树 → 右子树」",
                        "递归实现的核心是三步：访问根、递归左、递归右",
                        "时间复杂度 O(n)，空间复杂度取决于递归栈深度"))
                .chapters(List.of(
                        Chapter.builder().title("前序遍历定义").startTime("00:00").endTime("02:30")
                                .content("讲解根-左-右的访问顺序与直观示例").build(),
                        Chapter.builder().title("递归代码实现").startTime("02:30").endTime("05:10")
                                .content("演示递归版本的代码写法与边界条件").build(),
                        Chapter.builder().title("复杂度总结").startTime("05:10").endTime("")
                                .content("分析时间复杂度与空间复杂度").build()))
                .tokenCost(0)
                .build();
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
