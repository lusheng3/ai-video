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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private VideoTranscriptionMapper transcriptionMapper;
    @Mock private AiSummaryResultMapper summaryMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private AnalysisStateService stateService;
    @Mock private AudioExtractionService audioExtractionService;
    @Mock private AnalysisResultWriter resultWriter;
    @Mock private TaskEventService taskEventService;
    @Mock private AnalysisMetrics metrics;

    private AppProperties appProperties;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        analysisService = new AnalysisService(
                taskRecordMapper, transcriptionMapper, summaryMapper,
                rocketMQTemplate, stateService, audioExtractionService,
                resultWriter, appProperties, taskEventService, metrics);
    }

    @Test
    void shouldReturnDuplicateWithoutSendingMqWhenActiveKeyAlreadyExists() {
        when(taskRecordMapper.selectById(1L)).thenReturn(task(1L));
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(stateService.acquireActive(eq(1L), anyString())).thenReturn(false);

        AnalysisSubmitResponse response = analysisService.submit(1L, "总结重点", 1001L);

        assertEquals(TaskStatusEnum.QUEUED.getCode(), response.getStatus());
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Object.class));
        verify(metrics).submit("duplicate");
    }

    @Test
    void shouldReuseExistingSameGoalResultWithoutSendingMq() {
        VideoTaskRecord task = task(2L);
        when(taskRecordMapper.selectById(2L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(1L);
        when(summaryMapper.selectCount(any())).thenReturn(1L);

        AnalysisSubmitResponse response = analysisService.submit(2L, "总结重点", 1001L);

        assertTrue(response.isReused());
        assertEquals(TaskStatusEnum.SUCCESS.getCode(), response.getStatus());
        verify(resultWriter).markTaskSuccess(task);
        verify(resultWriter).cacheSuccess(task);
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void shouldSendMqAfterAtomicallyAcquiringActiveKey() {
        VideoTaskRecord task = task(3L);
        when(taskRecordMapper.selectById(3L)).thenReturn(task);
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(stateService.acquireActive(eq(3L), anyString())).thenReturn(true);
        when(rocketMQTemplate.syncSend(anyString(), any(Object.class)))
                .thenReturn(new SendResult(SendStatus.SEND_OK, "msg-1", "offset-1", null, 0L));

        AnalysisSubmitResponse response = analysisService.submit(3L, "总结重点", 1001L);

        assertEquals(TaskStatusEnum.QUEUED.getCode(), response.getStatus());
        verify(stateService).markProcessing(3L);
        verify(rocketMQTemplate).syncSend(eq("video-analysis-topic"), any(Object.class));
        verify(metrics).submit("accepted");
    }

    @Test
    void shouldRollbackActiveStateWhenMqSendFails() {
        when(taskRecordMapper.selectById(4L)).thenReturn(task(4L));
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(stateService.acquireActive(eq(4L), anyString())).thenReturn(true);
        when(rocketMQTemplate.syncSend(anyString(), any(Object.class)))
                .thenThrow(new RuntimeException("broker down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> analysisService.submit(4L, "总结重点", 1001L));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        verify(stateService).clearStatus(4L);
        verify(stateService).releaseActive(4L);
        verify(metrics).mqSendFailure();
    }

    @Test
    void shouldAllowOnlyOneWinnerAcrossConcurrentSubmissions() throws Exception {
        when(taskRecordMapper.selectById(5L)).thenAnswer(invocation -> task(5L));
        when(transcriptionMapper.selectCount(any())).thenReturn(0L);
        when(summaryMapper.selectCount(any())).thenReturn(0L);
        when(rocketMQTemplate.syncSend(anyString(), any(Object.class)))
                .thenReturn(new SendResult(SendStatus.SEND_OK, "msg-1", "offset-1", null, 0L));
        Set<Long> activeTasks = ConcurrentHashMap.newKeySet();
        when(stateService.acquireActive(anyLong(), anyString()))
                .thenAnswer(invocation -> activeTasks.add(invocation.getArgument(0)));

        int callers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    AnalysisSubmitResponse response = analysisService.submit(5L, "总结重点", 1001L);
                    if ("任务已进入异步分析队列".equals(response.getMessage())) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, accepted.get());
        verify(rocketMQTemplate, times(1)).syncSend(anyString(), any(Object.class));
    }

    private VideoTaskRecord task(Long id) {
        return VideoTaskRecord.builder()
                .id(id)
                .userId(1001L)
                .videoMd5(MD5)
                .fileName("course.mp4")
                .fileUrl("videos/" + MD5 + "/course.mp4")
                .totalChunks(3)
                .status(TaskStatusEnum.UPLOADED.getCode())
                .build();
    }
}
