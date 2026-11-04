package com.dovideo.client.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dovideo.client.AiClientException;
import com.dovideo.client.LlmClient;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.client.prompt.SummaryPromptBuilder;
import com.dovideo.config.AppProperties;
import com.dovideo.util.ExponentialBackoffRetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议的大模型客户端（默认对接 DeepSeek 官方 API）。
 * 通过修改 app.ai.llm.base-url 可无缝切换到硅基流动、通义千问等任意兼容服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient aiHttpClient;
    private final AppProperties appProperties;
    private final ExponentialBackoffRetry retry;

    @Override
    public SummaryResult summarize(String transcript, String userGoal) {
        AppProperties.LlmProps llm = appProperties.getAi().getLlm();
        return retry.execute("llm-summarize", () -> doSummarize(llm, transcript, userGoal));
    }

    private SummaryResult doSummarize(AppProperties.LlmProps llm, String transcript, String userGoal) {
        String requestJson = buildRequestJson(llm, transcript, userGoal);
        Request request = new Request.Builder()
                .url(normalizeBaseUrl(llm.getBaseUrl()) + "/chat/completions")
                .header("Authorization", "Bearer " + llm.getApiKey())
                .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseText = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new AiClientException(
                        "LLM 调用失败 httpStatus=" + response.code() + " body=" + truncate(responseText),
                        OpenAiCompatibleAsrClient.isRetryableStatus(response.code()),
                        response.code(),
                        null);
            }
            return parseResponse(responseText);
        } catch (AiClientException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AiClientException("LLM 网络异常: " + ex.getMessage(), true, null, ex);
        } catch (RuntimeException ex) {
            throw new AiClientException("LLM 调用异常: " + ex.getMessage(), false, null, ex);
        }
    }

    String buildRequestJson(AppProperties.LlmProps llm, String transcript, String userGoal) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SummaryPromptBuilder.SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", SummaryPromptBuilder.buildUserPrompt(transcript, userGoal)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", llm.getModel());
        payload.put("messages", messages);
        payload.put("temperature", llm.getTemperature());
        payload.put("max_tokens", llm.getMaxTokens());
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("stream", false);
        return JSON.toJSONString(payload);
    }

    SummaryResult parseResponse(String responseText) {
        JSONObject root = JSON.parseObject(responseText);
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AiClientException("LLM 响应缺少 choices 字段", false, null, null);
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        if (content == null || content.isBlank()) {
            throw new AiClientException("LLM 响应内容为空", false, null, null);
        }

        String jsonContent = extractJson(content);
        SummaryResult result;
        try {
            result = JSON.parseObject(jsonContent, SummaryResult.class);
        } catch (RuntimeException ex) {
            throw new AiClientException("LLM 输出的 JSON 无法解析: " + truncate(jsonContent), false, null, ex);
        }
        if (result == null) {
            throw new AiClientException("LLM 输出 JSON 为空", false, null, null);
        }
        if (result.getKeyPoints() == null) {
            result.setKeyPoints(new ArrayList<>());
        }
        if (result.getChapters() == null) {
            result.setChapters(new ArrayList<>());
        }

        JSONObject usage = root.getJSONObject("usage");
        result.setTokenCost(usage == null ? 0 : usage.getIntValue("total_tokens"));
        log.info("llm_summarize_success summaryLength={} keyPoints={} chapters={} tokens={}",
                result.getSummary() == null ? 0 : result.getSummary().length(),
                result.getKeyPoints().size(),
                result.getChapters().size(),
                result.getTokenCost());
        return result;
    }

    /**
     * 容错：模型偶尔会用 ```json 包裹输出，这里截取第一个 { 到最后一个 } 之间的内容。
     */
    static String extractJson(String content) {
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    static String normalizeBaseUrl(String baseUrl) {
        return OpenAiCompatibleAsrClient.normalizeBaseUrl(baseUrl);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
