package com.dovideo.consumer;

import com.dovideo.client.AiClientException;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import com.dovideo.service.AnalysisResultWriter;
import com.dovideo.service.AudioExtractionService;
import com.dovideo.service.TaskEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisConsumerTest {

    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @Mock private RedissonClient redissonClient;
    @Mock private RLock lock;
    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private VideoTranscriptionMapper transcriptionMapper;
    @Mock private AiSummaryResultMapper summaryMapper;
    @Mock private AudioExtractionService audioExtractionService;
    @Mock private AnalysisResultWriter resultWriter;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private TaskEventService taskEventService;

    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VideoAnalysisConsumer(
                redissonClient, taskRecordMapper, transcriptionMapper, summaryMapper,
                audioExtractionService, resultWriter, redisTemplate, taskEventService);
        when(redissonClient.getLock("lock:video:" + MD5)).thenReturn(lock);
    }

    @Test
    void shouldAckImmediatelyWhenLockNotAcquired() {
        when(lock.tryLock()).thenReturn(false);

        consumer.onMessage(message());

        verify(taskRecordMapper, never()).selectById(any());
    }

    @Test
    void shouldSkipWhenTaskAlreadySuccessAndUnlock() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.SUCCESS.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);

        consumer.onMessage(message());

        verify(audioExtractionService, never()).transcribeFromMinio(anyString(), any());
        verify(lock).unlock();
    }

    @Test
    void shouldReuseExistingResultWhenAnotherTaskAlreadyFinished() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(1L);
        when(summaryMapper.selectCount(any())).thenReturn(1L);

        consumer.onMessage(message());

        verify(resultWriter).markTaskSuccess(task);
        verify(audioExtractionService, never()).transcribeFromMinio(anyString(), any());
        verify(lock).unlock();
    }

    @Test
    void shouldRunBusinessAndPersistOnSuccess() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(audioExtractionService.transcribeFromMinio(eq("videos/" + MD5 + "/course.mp4"), any()))
                .thenReturn("逐字稿内容");
        SummaryResult summary = SummaryResult.builder().summary("总结").build();
        when(audioExtractionService.analyzeTranscript("逐字稿内容", "总结重点")).thenReturn(summary);

        consumer.onMessage(message());

        verify(resultWriter).persist(task, "逐字稿内容", summary);
        verify(lock).unlock();
    }

    @Test
    void shouldMarkRetryingAndThrowForMqRetryWhenBusinessFails() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(audioExtractionService.transcribeFromMinio(anyString(), any()))
                .thenThrow(new IllegalStateException("ASR 超时"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message()));

        verify(valueOperations).set(eq("video:status:" + MD5), eq("RETRYING"), any());
        verify(lock).unlock();
    }

    @Test
    void shouldMarkFailedDirectlyOnPermanentAiFailureWithoutMqRetry() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(audioExtractionService.transcribeFromMinio(anyString(), any()))
                .thenThrow(new AiClientException(
                        "ASR 调用失败 httpStatus=402 body=balance insufficient", false, 402, null));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 不抛异常 = 直接 ACK，不再浪费 3 次 MQ 重投
        consumer.onMessage(message());

        assertEquals(TaskStatusEnum.FAILED.getCode(), task.getStatus());
        assertTrue(task.getErrorMsg().contains("AI 服务调用失败"));
        verify(valueOperations).set(eq("video:status:" + MD5), startsWith("FAILED:"), any());
        verify(lock).unlock();
    }

    private AnalysisTaskMessage message() {
        return AnalysisTaskMessage.builder()
                .taskId(1L)
                .userId(1001L)
                .videoMd5(MD5)
                .minioPath("videos/" + MD5 + "/course.mp4")
                .userGoal("总结重点")
                .build();
    }

    private VideoTaskRecord task(int status) {
        return VideoTaskRecord.builder()
                .id(1L)
                .userId(1001L)
                .videoMd5(MD5)
                .fileUrl("videos/" + MD5 + "/course.mp4")
                .status(status)
                .build();
    }
}
