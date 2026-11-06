package com.dovideo.consumer;

import com.dovideo.client.AiClientException;
import com.dovideo.client.dto.SummaryResult;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.AnalysisTaskMessage;
import com.dovideo.entity.AiSummaryResult;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.entity.VideoTranscription;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.AiSummaryResultMapper;
import com.dovideo.mapper.VideoTaskRecordMapper;
import com.dovideo.mapper.VideoTranscriptionMapper;
import com.dovideo.service.AnalysisMetrics;
import com.dovideo.service.AnalysisResultWriter;
import com.dovideo.service.AnalysisStateService;
import com.dovideo.service.AudioExtractionService;
import com.dovideo.service.TaskEventService;
import com.dovideo.util.AnalysisTaskKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisConsumerTest {

    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String GOAL = "总结重点";

    @Mock private RedissonClient redissonClient;
    @Mock private RLock analysisLock;
    @Mock private RLock contentLock;
    @Mock private VideoTaskRecordMapper taskRecordMapper;
    @Mock private VideoTranscriptionMapper transcriptionMapper;
    @Mock private AiSummaryResultMapper summaryMapper;
    @Mock private AudioExtractionService audioExtractionService;
    @Mock private AnalysisResultWriter resultWriter;
    @Mock private TaskEventService taskEventService;
    @Mock private AnalysisStateService stateService;
    @Mock private AnalysisMetrics metrics;

    private VideoAnalysisConsumer consumer;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        consumer = new VideoAnalysisConsumer(
                redissonClient, taskRecordMapper, transcriptionMapper, summaryMapper,
                audioExtractionService, resultWriter, taskEventService,
                stateService, metrics, appProperties);
        when(redissonClient.getLock(AnalysisTaskKeys.analysisLock(
                MD5, AnalysisTaskKeys.goalDigest(GOAL)))).thenReturn(analysisLock);
        when(redissonClient.getLock(AnalysisTaskKeys.contentLock(MD5))).thenReturn(contentLock);
    }

    @Test
    void shouldRetryWhenAnalysisLockCannotBeAcquiredAndNoResultExists() throws Exception {
        when(analysisLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(taskRecordMapper.selectById(1L)).thenReturn(task(TaskStatusEnum.UPLOADED.getCode()));
        when(summaryMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message()));

        verify(stateService).markRetrying(1L);
        verify(analysisLock, never()).unlock();
    }

    @Test
    void shouldReuseSameGoalResultWhenAnotherConsumerFinishesFirst() throws Exception {
        when(analysisLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(analysisLock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.PROCESSING.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(summaryMapper.selectOne(any())).thenReturn(AiSummaryResult.builder().id(9L).build());

        consumer.onMessage(message());

        verify(resultWriter).markTaskSuccess(task);
        verify(resultWriter).cacheSuccess(task);
        verify(audioExtractionService, never()).transcribeFromMinio(anyString(), any());
        verify(analysisLock).unlock();
    }

    @Test
    void shouldReuseExistingTranscriptAndOnlyRunGoalSpecificAnalysis() throws Exception {
        when(analysisLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(contentLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(analysisLock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(transcriptionMapper.selectOne(any())).thenReturn(
                VideoTranscription.builder().videoMd5(MD5).transcriptionContent("已有逐字稿").build());
        SummaryResult summary = SummaryResult.builder().summary("目标总结").build();
        when(audioExtractionService.analyzeTranscript("已有逐字稿", GOAL)).thenReturn(summary);

        consumer.onMessage(message());

        verify(audioExtractionService, never()).transcribeFromMinio(anyString(), any());
        verify(audioExtractionService).analyzeTranscript("已有逐字稿", GOAL);
        verify(resultWriter).persist(task, "已有逐字稿", summary);
        verify(resultWriter).cacheSuccess(task);
        verify(contentLock).unlock();
        verify(analysisLock).unlock();
    }

    @Test
    void shouldBuildTranscriptOnceThenPersistGoalResult() throws Exception {
        when(analysisLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(contentLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(analysisLock.isHeldByCurrentThread()).thenReturn(true);
        VideoTaskRecord task = task(TaskStatusEnum.UPLOADED.getCode());
        when(taskRecordMapper.selectById(1L)).thenReturn(task);
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(transcriptionMapper.selectOne(any())).thenReturn(null);
        when(audioExtractionService.transcribeFromMinio(eq("videos/" + MD5 + "/course.mp4"), any()))
                .thenReturn("新逐字稿");
        SummaryResult summary = SummaryResult.builder().summary("目标总结").build();
        when(audioExtractionService.analyzeTranscript("新逐字稿", GOAL)).thenReturn(summary);

        consumer.onMessage(message());

        verify(resultWriter).saveTranscriptionIfAbsent(task, "新逐字稿");
        verify(resultWriter).persist(task, "新逐字稿", summary);
        verify(resultWriter).cacheSuccess(task);
    }

    @Test
    void shouldFailPermanentlyWithoutMqRetryForNonRetryableAiError() throws Exception {
        when(analysisLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(contentLock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(analysisLock.isHeldByCurrentThread()).thenReturn(true);
        when(contentLock.isHeldByCurrentThread()).thenReturn(true);
        when(taskRecordMapper.selectById(1L)).thenReturn(task(TaskStatusEnum.UPLOADED.getCode()));
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(transcriptionMapper.selectOne(any())).thenReturn(null);
        when(audioExtractionService.transcribeFromMinio(anyString(), any()))
                .thenThrow(new AiClientException("402", false, 402, null));

        assertDoesNotThrow(() -> consumer.onMessage(message()));

        verify(stateService).markFailed(eq(1L), anyString());
        verify(stateService).releaseActive(1L);
    }

    private AnalysisTaskMessage message() {
        return AnalysisTaskMessage.builder()
                .taskId(1L)
                .userId(1001L)
                .videoMd5(MD5)
                .minioPath("videos/" + MD5 + "/course.mp4")
                .userGoal(GOAL)
                .goalDigest(AnalysisTaskKeys.goalDigest(GOAL))
                .build();
    }

    private VideoTaskRecord task(int status) {
        return VideoTaskRecord.builder()
                .id(1L)
                .userId(1001L)
                .videoMd5(MD5)
                .fileUrl("videos/" + MD5 + "/course.mp4")
                .userGoal(GOAL)
                .goalDigest(AnalysisTaskKeys.goalDigest(GOAL))
                .status(status)
                .build();
    }
}
