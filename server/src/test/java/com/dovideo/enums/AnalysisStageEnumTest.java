package com.dovideo.enums;

import com.dovideo.dto.AnalysisEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStageEnumTest {

    @Test
    void stageShouldMapToDatabaseStatusAndProgress() {
        assertEquals(TaskStatusEnum.QUEUED.getCode(), AnalysisStageEnum.QUEUED.getStatus());
        assertEquals(0, AnalysisStageEnum.QUEUED.getDefaultProgress());

        assertEquals(TaskStatusEnum.PROCESSING.getCode(), AnalysisStageEnum.ASR.getStatus());
        assertEquals(30, AnalysisStageEnum.ASR.getDefaultProgress());

        assertEquals(TaskStatusEnum.SUCCESS.getCode(), AnalysisStageEnum.COMPLETED.getStatus());
        assertEquals(100, AnalysisStageEnum.COMPLETED.getDefaultProgress());

        assertEquals(TaskStatusEnum.FAILED.getCode(), AnalysisStageEnum.FAILED.getStatus());
    }

    @Test
    void terminalEventShouldOnlyBeSuccessOrFailed() {
        assertFalse(event(1).terminal());
        assertFalse(event(2).terminal());
        assertTrue(event(3).terminal());
        assertTrue(event(4).terminal());
    }

    @Test
    void fromShouldIgnoreCaseAndUnknownValue() {
        assertEquals(AnalysisStageEnum.ASR, AnalysisStageEnum.from("asr"));
        assertEquals(AnalysisStageEnum.COMPLETED, AnalysisStageEnum.from("COMPLETED"));
        assertEquals(null, AnalysisStageEnum.from("NOT_A_STAGE"));
        assertEquals(null, AnalysisStageEnum.from(null));
    }

    private AnalysisEvent event(int status) {
        return AnalysisEvent.builder().taskId(1L).status(status).build();
    }
}
