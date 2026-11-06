package com.dovideo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AnalysisTaskKeysTest {

    @Test
    void sameNormalizedGoalProducesSameDigestAndDifferentGoalsStaySeparated() {
        String first = AnalysisTaskKeys.goalDigest("  生成复习笔记  ");
        String second = AnalysisTaskKeys.goalDigest("生成复习笔记");
        String different = AnalysisTaskKeys.goalDigest("提取操作步骤");

        assertEquals(first, second);
        assertNotEquals(first, different);
        assertEquals(64, first.length());
    }
}
