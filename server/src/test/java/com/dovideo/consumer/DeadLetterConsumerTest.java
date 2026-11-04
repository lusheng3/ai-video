package com.dovideo.consumer;

import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterConsumerTest {

    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void shouldMarkTaskFailedAndWriteRedisFailureReason() {
        VideoTaskRecord task = VideoTaskRecord.builder().id(9L).videoMd5("abc").status(2).build();
        when(taskRecordMapper.selectById(9L)).thenReturn(task);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        new DeadLetterConsumer(taskRecordMapper, redisTemplate).onMessage(
                AnalysisTaskMessage.builder().taskId(9L).videoMd5("abc").build());

        assertEquals(TaskStatusEnum.FAILED.getCode(), task.getStatus());
        assertTrue(task.getErrorMsg().contains("死信"));
        verify(taskRecordMapper).updateById(task);
        verify(valueOperations).set(eq("video:status:abc"), org.mockito.ArgumentMatchers.startsWith("FAILED:"), any());
    }
}
