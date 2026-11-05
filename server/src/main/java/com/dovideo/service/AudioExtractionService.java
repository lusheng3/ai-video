package com.dovideo.service;

import com.dovideo.client.AsrClient;
import com.dovideo.client.LlmClient;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import com.dovideo.enums.AnalysisStageEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 音视频处理服务：
 * 1. 从 MinIO 下载视频到本地临时文件；
 * 2. FFmpeg 提取 16kHz 单声道音频，并按 asrSegmentSeconds（默认 300 秒）切段；
 * 3. 逐段调用 ASR，拼接为带时间范围的完整逐字稿；
 * 4. 调用 LLM 生成结构化总结；
 * 5. finally 中删除所有临时文件与临时目录，保证服务无状态化。
 *
 * <p>为什么要分段：11 分钟视频提取出的 16kHz WAV 约 21MB，30 分钟约 56MB，
 * 1 小时超过 100MB，直接上传会超过多数 ASR 接口的单文件上限。分段后每段约 9.4MB，
 * 既规避大小限制，也便于失败时只重试单段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioExtractionService {

    private static final long PROCESS_TIMEOUT_MINUTES = 30;
    private static final int SAMPLE_RATE = 16000;

    private final AppProperties appProperties;
    private final MinioService minioService;
    private final AsrClient asrClient;
    private final LlmClient llmClient;

    /**
     * 转写进度回调：让音视频处理层只关心「当前第几段」，
     * 由消费者负责把阶段事件推给前端，避免处理层直接依赖 SSE/Redis。
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(AnalysisStageEnum stage, String message, Integer progress);
    }

    /**
     * 兼容旧调用：不关心进度时保持原签名。
     */
    public String transcribeFromMinio(String objectKey) {
        return transcribeFromMinio(objectKey, null);
    }

    /**
     * 从 MinIO 对象中提取音频、分段转写，并在关键节点回调进度。
     */
    public String transcribeFromMinio(String objectKey, ProgressListener listener) {
        Path videoTemp = null;
        Path audioDir = null;
        try {
            videoTemp = Files.createTempFile("dovideo-video-", ".mp4");
            try (InputStream inputStream = minioService.getObjectStream(objectKey)) {
                Files.copy(inputStream, videoTemp, StandardCopyOption.REPLACE_EXISTING);
            }

            // 压测/本地开发模式：跳过 FFmpeg 与分段，直接返回 Mock 逐字稿
            if (appProperties.isMockEnabled()) {
                report(listener, AnalysisStageEnum.ASR, "Mock 模式正在生成逐字稿", 30);
                Path mockAudio = Files.createTempFile("dovideo-audio-", ".wav");
                Files.writeString(mockAudio, "mock-audio");
                try {
                    return asrClient.transcribe(mockAudio);
                } finally {
                    deleteQuietly(mockAudio);
                }
            }

            double durationSeconds = probeDurationSeconds(videoTemp);
            audioDir = Files.createTempDirectory("dovideo-audio-segments-");
            List<Path> segments = splitAudio(videoTemp, audioDir);
            log.info("audio_segmented video={} durationSeconds={} segments={}",
                    objectKey, durationSeconds, segments.size());
            report(listener, AnalysisStageEnum.AUDIO_SEGMENTED,
                    "音频已切分为 " + segments.size() + " 段，开始逐段转写", 20);

            StringBuilder transcript = new StringBuilder();
            int segmentSeconds = appProperties.getAi().getAsrSegmentSeconds();
            for (int index = 0; index < segments.size(); index++) {
                double start = (double) index * segmentSeconds;
                double end = durationSeconds > 0
                        ? Math.min(start + segmentSeconds, durationSeconds)
                        : start + segmentSeconds;
                int progress = 20 + (int) Math.round(58.0 * index / segments.size());
                report(listener, AnalysisStageEnum.ASR,
                        "正在转写第 " + (index + 1) + "/" + segments.size() + " 段音频", progress);
                String part = asrClient.transcribe(segments.get(index));
                if (part == null || part.isBlank()) {
                    continue;
                }
                transcript.append('[').append(formatTime(start)).append('-').append(formatTime(end)).append("]\n");
                transcript.append(part.trim()).append("\n\n");
            }
            return transcript.toString().trim();
        } catch (IOException ex) {
            throw new IllegalStateException("视频下载或音频提取失败: " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(videoTemp);
            deleteDirectoryQuietly(audioDir);
        }
    }

    /**
     * FFmpeg 提取 16kHz 单声道音频并按段切割，输出 segment-000.wav、segment-001.wav ...
     */
    List<Path> splitAudio(Path videoFile, Path outputDir) {
        AppProperties.AiProps ai = appProperties.getAi();
        Path pattern = outputDir.resolve("segment-%03d.wav");
        List<String> command = List.of(
                ai.getFfmpegPath(),
                "-y",
                "-i", videoFile.toString(),
                "-vn",
                "-ac", "1",
                "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "segment",
                "-segment_time", String.valueOf(ai.getAsrSegmentSeconds()),
                "-reset_timestamps", "1",
                "-segment_format", "wav",
                pattern.toString()
        );
        runProcess(command, "FFmpeg 音频分段", PROCESS_TIMEOUT_MINUTES);

        try (Stream<Path> stream = Files.list(outputDir)) {
            List<Path> segments = stream
                    .filter(path -> path.getFileName().toString().endsWith(".wav"))
                    .sorted()
                    .toList();
            if (segments.isEmpty()) {
                throw new IllegalStateException("FFmpeg 未生成任何音频分段");
            }
            return segments;
        } catch (IOException ex) {
            throw new IllegalStateException("读取音频分段目录失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 用 ffprobe 读取视频总时长；失败时返回 -1，由调用方退化为按固定段长估算
     */
    double probeDurationSeconds(Path videoFile) {
        AppProperties.AiProps ai = appProperties.getAi();
        try {
            ProcessResult result = runProcess(List.of(
                    ai.getFfprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoFile.toString()
            ), "FFprobe 读取时长", 2);
            String text = result.output().trim();
            if (text.isEmpty()) {
                return -1;
            }
            return Double.parseDouble(text.split("\\s+")[0]);
        } catch (RuntimeException ex) {
            log.warn("ffprobe_duration_failed video={} reason={}", videoFile, ex.getMessage());
            return -1;
        }
    }

    /**
     * 转写 + 结构化总结（步骤 7 的消费者会调用）
     */
    public SummaryResult analyzeTranscript(String transcript, String userGoal) {
        return llmClient.summarize(transcript, userGoal);
    }

    private ProcessResult runProcess(List<String> command, String action, long timeoutMinutes) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(new ArrayList<>(command));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(action + " 超时（超过 " + timeoutMinutes + " 分钟）");
            }
            String output = new String(outputBytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(action + " 失败，exitCode="
                        + process.exitValue() + "，output=" + truncate(output));
            }
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException ex) {
            throw new IllegalStateException(action + " 执行失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + " 被中断", ex);
        }
    }

    static String formatTime(double seconds) {
        int totalSeconds = (int) Math.round(Math.max(0, seconds));
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int secs = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private void report(ProgressListener listener,
                        AnalysisStageEnum stage,
                        String message,
                        Integer progress) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(stage, message, progress);
        } catch (RuntimeException ex) {
            // 进度推送失败不能影响主流程（转写与总结才是任务本身）
            log.warn("audio_progress_report_failed stage={} reason={}", stage, ex.getMessage());
        }
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

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ex) {
            log.warn("temp_dir_delete_failed path={} reason={}", directory, ex.getMessage());
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
