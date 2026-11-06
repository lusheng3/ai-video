package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.config.AppProperties;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisRecoveryJobTest {

    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private AnalysisStateService stateService;
    @Mock private TaskEventService taskEventService;
    @Mock private AnalysisMetrics metrics;

    @Test
    void shouldFailStaleTaskWhenNoActiveKeyExists() {
        VideoTaskRecord task = VideoTaskRecord.builder()
                .id(10L)
                .videoMd5("abc")
                .status(TaskStatusEnum.PROCESSING.getCode())
                .build();
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(task));
        when(stateService.isActive(10L)).thenReturn(false);
        AnalysisRecoveryJob job = new AnalysisRecoveryJob(
                taskRecordMapper, stateService, taskEventService, metrics, new AppProperties());

        int recovered = job.recoverStaleTasks();

        assertEquals(1, recovered);
        verify(taskRecordMapper).update(isNull(), any(UpdateWrapper.class));
        verify(stateService).markFailed(any(), any());
        verify(metrics).staleRecovered();
    }

    @Test
    void shouldKeepStaleLookingTaskWhenActiveOwnerStillExists() {
        VideoTaskRecord task = VideoTaskRecord.builder()
                .id(11L)
                .videoMd5("def")
                .status(TaskStatusEnum.PROCESSING.getCode())
                .build();
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(task));
        when(stateService.isActive(11L)).thenReturn(true);
        AnalysisRecoveryJob job = new AnalysisRecoveryJob(
                taskRecordMapper, stateService, taskEventService, metrics, new AppProperties());

        assertEquals(0, job.recoverStaleTasks());
        verify(taskRecordMapper, never()).update(any(), any());
    }
}
