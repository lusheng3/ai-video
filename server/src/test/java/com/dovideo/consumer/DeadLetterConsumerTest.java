package com.dovideo.consumer;

import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.service.AnalysisStateService;
import com.dovideo.service.TaskEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterConsumerTest {

    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private TaskEventService taskEventService;
    @Mock private AnalysisStateService stateService;

    @Test
    void shouldMarkTaskFailedAndWriteRedisFailureReason() {
        VideoTaskRecord task = VideoTaskRecord.builder().id(9L).videoMd5("abc").status(2).build();
        when(taskRecordMapper.selectById(9L)).thenReturn(task);

        new DeadLetterConsumer(taskRecordMapper, taskEventService, stateService).onMessage(
                AnalysisTaskMessage.builder().taskId(9L).videoMd5("abc").userGoal("总结重点").build());

        assertEquals(TaskStatusEnum.FAILED.getCode(), task.getStatus());
        assertTrue(task.getErrorMsg().contains("死信"));
        verify(taskRecordMapper).updateById(task);
        verify(stateService).markFailed(eq(9L), org.mockito.ArgumentMatchers.contains("死信"));
        verify(stateService).releaseActive(9L);
    }
}
