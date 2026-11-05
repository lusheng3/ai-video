package com.dovideo.service;

import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.AnalysisSubmitResponse;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private VideoTranscriptionMapper transcriptionMapper;
    @Mock private AiSummaryResultMapper summaryMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private AudioExtractionService audioExtractionService;
    @Mock private AnalysisResultWriter resultWriter;
    @Mock private TaskEventService taskEventService;

    private AppProperties appProperties;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        analysisService = new AnalysisService(
                taskRecordMapper, transcriptionMapper, summaryMapper,
                rocketMQTemplate, redisTemplate, audioExtractionService,
                resultWriter, appProperties, taskEventService);
    }

    @Test
    void shouldReturnImmediatelyWhenRedisSaysProcessingAndNotSendMq() {
        VideoTaskRecord task = task(1L, 1001L, TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("video:status:" + MD5)).thenReturn("PROCESSING");

        AnalysisSubmitResponse response = analysisService.submit(1L, "总结重点", 1001L);

        assertEquals(1L, response.getTaskId());
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void shouldReuseExistingResultWithoutSendingMq() {
        VideoTaskRecord task = task(2L, 1001L, TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(2L)).thenReturn(task);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("video:status:" + MD5)).thenReturn(null);
        when(transcriptionMapper.selectCount(any())).thenReturn(1L);
        when(summaryMapper.selectCount(any())).thenReturn(1L);

        AnalysisSubmitResponse response = analysisService.submit(2L, "总结重点", 1001L);

        assertTrue(response.isReused());
        verify(resultWriter).markTaskSuccess(task);
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void shouldSendMqAndMarkQueuedOnHappyPath() {
        VideoTaskRecord task = task(3L, 1001L, TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(3L)).thenReturn(task);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("video:status:" + MD5)).thenReturn(null);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        SendResult sendResult = new SendResult(SendStatus.SEND_OK, "msg-1", "offset-1", null, 0L);
        when(rocketMQTemplate.syncSend(anyString(), any(Object.class))).thenReturn(sendResult);

        AnalysisSubmitResponse response = analysisService.submit(3L, "总结重点", 1001L);

        assertEquals(TaskStatusEnum.QUEUED.getCode(), response.getStatus());
        assertEquals(TaskStatusEnum.QUEUED.getCode(), task.getStatus());
        verify(taskRecordMapper, org.mockito.Mockito.atLeast(1)).updateById(task);
        verify(valueOperations).set(eq("video:status:" + MD5), eq("PROCESSING"), eq(Duration.ofHours(1)));
        verify(rocketMQTemplate).syncSend(eq("video-analysis-topic"), any(Object.class));
    }

    @Test
    void shouldRollbackStatusAndThrow503WhenMqSendFails() {
        VideoTaskRecord task = task(4L, 1001L, TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(4L)).thenReturn(task);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("video:status:" + MD5)).thenReturn(null);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(rocketMQTemplate.syncSend(anyString(), any(Object.class)))
                .thenThrow(new RuntimeException("broker down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> analysisService.submit(4L, "总结重点", 1001L));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        assertEquals(TaskStatusEnum.UPLOADED.getCode(), task.getStatus());
        verify(redisTemplate).delete("video:status:" + MD5);
    }

    private VideoTaskRecord task(Long id, Long userId, int status) {
        return VideoTaskRecord.builder()
                .id(id)
                .userId(userId)
                .videoMd5(MD5)
                .fileName("course.mp4")
                .fileUrl("videos/" + MD5 + "/course.mp4")
                .totalChunks(3)
                .status(status)
                .build();
    }
}
