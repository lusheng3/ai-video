package com.dovideo.client.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dovideo.client.AiClientException;
import com.dovideo.client.AsrClient;
import com.dovideo.config.AppProperties;
import com.dovideo.util.ExponentialBackoffRetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OpenAI 兼容协议的语音转写客户端（默认对接硅基流动 SenseVoice）。
 * 也可无缝切换到阿里云 DashScope、本地 faster-whisper 等提供 /audio/transcriptions 的服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAsrClient implements AsrClient {

    private final OkHttpClient aiHttpClient;
    private final AppProperties appProperties;
    private final ExponentialBackoffRetry retry;

    @Override
    public String transcribe(Path audioFile) {
        AppProperties.AsrProps asr = appProperties.getAi().getAsr();
        return retry.execute("asr-transcribe", () -> doTranscribe(asr, audioFile));
    }

    private String doTranscribe(AppProperties.AsrProps asr, Path audioFile) {
        try {
            byte[] audioBytes = Files.readAllBytes(audioFile);
            RequestBody fileBody = RequestBody.create(audioBytes, MediaType.parse("application/octet-stream"));
            MultipartBody multipartBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.getFileName().toString(), fileBody)
                    .addFormDataPart("model", asr.getModel())
                    .addFormDataPart("language", asr.getLanguage())
                    .build();

            Request request = new Request.Builder()
                    .url(normalizeBaseUrl(asr.getBaseUrl()) + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + asr.getApiKey())
                    .post(multipartBody)
                    .build();

            try (Response response = aiHttpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseText = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new AiClientException(
                            "ASR 调用失败 httpStatus=" + response.code() + " body=" + truncate(responseText),
                            isRetryableStatus(response.code()),
                            response.code(),
                            null);
                }
                JSONObject json = JSON.parseObject(responseText);
                String text = json.getString("text");
                if (text == null || text.isBlank()) {
                    throw new AiClientException("ASR 返回内容为空", true, response.code(), null);
                }
                log.info("asr_transcribe_success chars={}", text.length());
                return text;
            }
        } catch (AiClientException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AiClientException("ASR 网络异常: " + ex.getMessage(), true, null, ex);
        } catch (RuntimeException ex) {
            throw new AiClientException("ASR 调用异常: " + ex.getMessage(), false, null, ex);
        }
    }

    static boolean isRetryableStatus(int code) {
        return code == 429 || code >= 500;
    }

    static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
