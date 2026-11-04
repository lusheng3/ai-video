package com.dovideo.client.impl;

import com.dovideo.client.AiClientException;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import com.dovideo.util.ExponentialBackoffRetry;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmClientTest {

    private MockWebServer server;
    private AppProperties appProperties;
    private List<Long> sleeps;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        appProperties = new AppProperties();
        appProperties.getAi().getLlm().setBaseUrl(server.url("/").toString());
        appProperties.getAi().getLlm().setApiKey("test-key");
        appProperties.getAi().getLlm().setModel("deepseek-chat");

        sleeps = new ArrayList<>();
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(3, 1000L, sleeps::add);
        client = new OpenAiCompatibleLlmClient(new OkHttpClient(), appProperties, retry);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldParseStructuredJsonSummary() {
        String llmContent = """
                {"summary":"本视频讲解前序遍历。","keyPoints":["根-左-右","递归实现"],"chapters":[{"title":"定义","startTime":"00:00","endTime":"02:30","content":"讲解遍历顺序"}]}
                """;
        String apiResponse = """
                {"choices":[{"message":{"role":"assistant","content":%s}}],"usage":{"total_tokens":1234}}
                """.formatted(com.alibaba.fastjson2.JSON.toJSONString(llmContent));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(apiResponse));

        SummaryResult result = client.summarize("00:00 今天讲前序遍历...", "提炼核心考点");

        assertEquals("本视频讲解前序遍历。", result.getSummary());
        assertEquals(2, result.getKeyPoints().size());
        assertEquals(1, result.getChapters().size());
        assertEquals("定义", result.getChapters().get(0).getTitle());
        assertEquals(1234, result.getTokenCost());
        assertEquals(0, sleeps.size());
    }

    @Test
    void shouldRetryOn429ThenSucceed() {
        String llmContent = "{\"summary\":\"重试成功\",\"keyPoints\":[],\"chapters\":[]}";
        String okResponse = """
                {"choices":[{"message":{"content":%s}}],"usage":{"total_tokens":10}}
                """.formatted(com.alibaba.fastjson2.JSON.toJSONString(llmContent));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(okResponse));

        SummaryResult result = client.summarize("transcript", "goal");

        assertEquals("重试成功", result.getSummary());
        assertEquals(List.of(1000L), sleeps);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void shouldNotRetryOn401AndThrowImmediately() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        AiClientException ex = assertThrows(AiClientException.class,
                () -> client.summarize("transcript", "goal"));

        assertFalse(ex.isRetryable());
        assertEquals(401, ex.getHttpStatus());
        assertEquals(List.of(), sleeps);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void shouldStripMarkdownFencesAroundJson() {
        String llmContent = "```json\n{\"summary\":\"带围栏\",\"keyPoints\":[\"a\"],\"chapters\":[]}\n```";
        String apiResponse = """
                {"choices":[{"message":{"content":%s}}],"usage":{"total_tokens":5}}
                """.formatted(com.alibaba.fastjson2.JSON.toJSONString(llmContent));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(apiResponse));

        SummaryResult result = client.summarize("t", "g");

        assertEquals("带围栏", result.getSummary());
        assertTrue(result.getKeyPoints().contains("a"));
    }
}
