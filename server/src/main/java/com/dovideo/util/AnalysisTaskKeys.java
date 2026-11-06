package com.dovideo.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AnalysisTaskKeys {

    private AnalysisTaskKeys() {
    }

    public static String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("分析目标不能为空");
        }
        String normalized = goal.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("分析目标不能超过500个字符");
        }
        return normalized;
    }

    public static String goalDigest(String goal) {
        return sha256(normalizeGoal(goal));
    }

    public static String active(Long taskId) {
        return "analysis:active:task:" + taskId;
    }

    public static String status(Long taskId) {
        return "video:status:" + taskId;
    }

    public static String analysisLock(String videoMd5, String goalDigest) {
        return "lock:analysis:" + videoMd5 + ":" + goalDigest;
    }

    public static String contentLock(String videoMd5) {
        return "lock:analysis-content:" + videoMd5;
    }

    public static String completed(String videoMd5, String goalDigest) {
        return "analysis:completed:" + videoMd5 + ":" + goalDigest;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
