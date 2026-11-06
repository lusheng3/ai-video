package com.dovideo.service;

import com.dovideo.config.AppProperties;
import com.dovideo.util.AnalysisTaskKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AnalysisStateService {

    public static final String PROCESSING = "PROCESSING";
    public static final String RETRYING = "RETRYING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED_PREFIX = "FAILED:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;

    public boolean acquireActive(Long taskId, String goalDigest) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                AnalysisTaskKeys.active(taskId),
                goalDigest,
                Duration.ofHours(appProperties.getAnalysis().getActiveTtlHours())));
    }

    public boolean isActive(Long taskId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(AnalysisTaskKeys.active(taskId)));
    }

    public String activeGoalDigest(Long taskId) {
        return redisTemplate.opsForValue().get(AnalysisTaskKeys.active(taskId));
    }

    public void refreshActive(Long taskId) {
        redisTemplate.expire(
                AnalysisTaskKeys.active(taskId),
                Duration.ofHours(appProperties.getAnalysis().getActiveTtlHours()));
    }

    public void releaseActive(Long taskId) {
        redisTemplate.delete(AnalysisTaskKeys.active(taskId));
    }

    public void markProcessing(Long taskId) {
        mark(taskId, PROCESSING);
    }

    public void markRetrying(Long taskId) {
        mark(taskId, RETRYING);
    }

    public void markSuccess(Long taskId, String videoMd5, String goalDigest) {
        mark(taskId, SUCCESS);
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.completed(videoMd5, goalDigest),
                String.valueOf(taskId),
                Duration.ofDays(appProperties.getAnalysis().getCompletedTtlDays()));
    }

    public void markFailed(Long taskId, String reason) {
        mark(taskId, FAILED_PREFIX + (reason == null ? "分析失败" : reason));
    }

    public String readStatus(Long taskId) {
        return redisTemplate.opsForValue().get(AnalysisTaskKeys.status(taskId));
    }

    public void clearStatus(Long taskId) {
        redisTemplate.delete(AnalysisTaskKeys.status(taskId));
    }

    public boolean isCompleted(String videoMd5, String goalDigest) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.completed(videoMd5, goalDigest)));
    }

    public Long completedTaskId(String videoMd5, String goalDigest) {
        String value = redisTemplate.opsForValue().get(
                AnalysisTaskKeys.completed(videoMd5, goalDigest));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            redisTemplate.delete(AnalysisTaskKeys.completed(videoMd5, goalDigest));
            return null;
        }
    }

    private void mark(Long taskId, String value) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.status(taskId),
                value,
                Duration.ofHours(appProperties.getAnalysis().getStatusTtlHours()));
    }
}
