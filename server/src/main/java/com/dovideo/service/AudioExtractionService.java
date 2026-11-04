package com.dovideo.service;

import com.dovideo.client.AsrClient;
import com.dovideo.client.LlmClient;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 音视频处理服务：从 MinIO 下载视频 -> FFmpeg 提取 16kHz 单声道音频 -> 清理本地临时文件，保证服务无状态化。
 * 开启 app.mock-enabled=true 时跳过 FFmpeg 调用，方便本地无 FFmpeg 环境直接跑通全链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioExtractionService {

    private final AppProperties appProperties;
    private final MinioService minioService;
    private final AsrClient asrClient;
    private final LlmClient llmClient;

    private static final long FFMPEG_TIMEOUT_MINUTES = 10;

    /**
     * 从 MinIO 对象中提取音频并转写为逐字稿
     */
    public String transcribeFromMinio(String objectKey) {
        Path videoTemp = null;
        Path audioTemp = null;
        try {
            videoTemp = Files.createTempFile("dovideo-video-", ".mp4");
            try (var inputStream = minioService.getObjectStream(objectKey)) {
                Files.copy(inputStream, videoTemp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            audioTemp = extractAudio(videoTemp);
            return asrClient.transcribe(audioTemp);
        } catch (IOException ex) {
            throw new IllegalStateException("视频下载或音频提取失败: " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(videoTemp);
            deleteQuietly(audioTemp);
        }
    }

    /**
     * 调用 FFmpeg 提取 16kHz 单声道 wav 音频
     */
    public Path extractAudio(Path videoFile) {
        try {
            Path audioFile = Files.createTempFile("dovideo-audio-", ".wav");
            if (appProperties.isMockEnabled()) {
                // 压测/本地开发模式：写一小段占位字节，跳过真实 FFmpeg 调用
                Files.writeString(audioFile, "mock-audio");
                log.info("mock_audio_extracted video={} audio={}", videoFile, audioFile);
                return audioFile;
            }
            Files.deleteIfExists(audioFile);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    appProperties.getAi().getFfmpegPath(),
                    "-y",
                    "-i", videoFile.toString(),
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "wav",
                    audioFile.toString()
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 提取音频超时（超过 " + FFMPEG_TIMEOUT_MINUTES + " 分钟）");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg 提取音频失败，exitCode=" + process.exitValue());
            }
            log.info("ffmpeg_extract_audio_success video={} audio={} size={}",
                    videoFile, audioFile, Files.size(audioFile));
            return audioFile;
        } catch (IOException ex) {
            throw new IllegalStateException("FFmpeg 执行失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg 执行被中断", ex);
        }
    }

    /**
     * 转写 + 结构化总结（步骤 7 的消费者会调用）
     */
    public SummaryResult analyzeTranscript(String transcript, String userGoal) {
        return llmClient.summarize(transcript, userGoal);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("temp_file_delete_failed path={} reason={}", path, ex.getMessage());
        }
    }
}
