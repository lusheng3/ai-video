package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.dto.UploadCheckResponse;
import com.dovideo.dto.UploadMergeRequest;
import com.dovideo.dto.UploadMergeResponse;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.VideoTaskRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final String SAMPLE_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    @Mock
    private VideoTaskRecordMapper taskRecordMapper;
    @Mock
    private MinioService minioService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock mergeLock;

    private AppProperties appProperties;
    private UploadService uploadService;
    private UploadReconciliationJob reconciliationJob;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        uploadService = new UploadService(
                taskRecordMapper, minioService, redisTemplate, redissonClient, appProperties);
        reconciliationJob = new UploadReconciliationJob(
                taskRecordMapper, minioService, redisTemplate);
    }

    @Test
    void shouldReturnDeduplicatedTrueWhenFileMd5AlreadyExistsInMySql() {
        VideoTaskRecord existing = VideoTaskRecord.builder()
                .id(88L)
                .userId(1001L)
                .videoMd5(SAMPLE_MD5)
                .fileName("java-course.mp4")
                .fileUrl("videos/" + SAMPLE_MD5 + "/java-course.mp4")
                .status(TaskStatusEnum.SUCCESS.getCode())
                .build();
        when(taskRecordMapper.selectOne(any())).thenReturn(existing);

        UploadCheckResponse resp = uploadService.checkUpload(SAMPLE_MD5, "java-course.mp4", 10485760L, 2, 1001L);

        assertTrue(resp.isDeduplicated());
        assertEquals(88L, resp.getTaskId());
        assertEquals("videos/" + SAMPLE_MD5 + "/java-course.mp4", resp.getFileUrl());
    }

    @Test
    void shouldReturnUploadedChunkSetFromRedisForBreakpointResume() {
        when(taskRecordMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("upload:task:" + SAMPLE_MD5)).thenReturn(Set.of("0", "1", "4"));

        UploadCheckResponse resp = uploadService.checkUpload(SAMPLE_MD5, "java-course.mp4", 52428800L, 10, 1001L);

        assertFalse(resp.isDeduplicated());
        assertEquals(Set.of(0, 1, 4), resp.getUploadedChunks());
    }

    @Test
    void shouldRejectCorruptedChunkWhenChunkMd5Mismatches() {
        byte[] content = "hello-chunk-0".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile chunkFile = new MockMultipartFile("file", "chunk0", "application/octet-stream", content);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                uploadService.uploadChunk(SAMPLE_MD5, 0, 3, "00000000000000000000000000000000", chunkFile));

        assertEquals(ErrorCode.CHUNK_CORRUPTED, ex.getErrorCode());
        verify(minioService, never()).putChunkObject(any(), anyInt(), any(), anyLong());
    }

    @Test
    void shouldWriteToMinioFirstThenRecordChunkIndexInRedisSet() {
        byte[] content = "valid-chunk-data".getBytes(StandardCharsets.UTF_8);
        String validChunkMd5 = uploadService.calculateMd5Hex(content);
        MockMultipartFile chunkFile = new MockMultipartFile("file", "chunk1", "application/octet-stream", content);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        uploadService.uploadChunk(SAMPLE_MD5, 1, 5, validChunkMd5, chunkFile);

        // 验证严格顺序：先落盘 MinIO，后写入 Redis Set 并设置 24 小时过期
        InOrder inOrder = inOrder(minioService, setOperations, redisTemplate);
        inOrder.verify(minioService).putChunkObject(eq(SAMPLE_MD5), eq(1), any(), eq((long) content.length));
        inOrder.verify(setOperations).add("upload:task:" + SAMPLE_MD5, "1");
        inOrder.verify(redisTemplate).expire("upload:task:" + SAMPLE_MD5, Duration.ofHours(24));
    }

    @Test
    void shouldAcquireMergeLockComposeChunksAndCleanup() {
        when(redissonClient.getLock("dovideo:merge:" + SAMPLE_MD5)).thenReturn(mergeLock);
        when(mergeLock.tryLock()).thenReturn(true);
        when(mergeLock.isHeldByCurrentThread()).thenReturn(true);
        when(taskRecordMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("upload:task:" + SAMPLE_MD5)).thenReturn(3L);
        when(minioService.videoObjectKey(SAMPLE_MD5, "lecture.mp4"))
                .thenReturn("videos/" + SAMPLE_MD5 + "/lecture.mp4");
        when(minioService.composeChunks(SAMPLE_MD5, 3, "lecture.mp4"))
                .thenReturn("videos/" + SAMPLE_MD5 + "/lecture.mp4");
        when(minioService.calculateObjectMd5("videos/" + SAMPLE_MD5 + "/lecture.mp4"))
                .thenReturn(SAMPLE_MD5);
        doAnswer(inv -> {
            VideoTaskRecord r = inv.getArgument(0);
            r.setId(500L);
            return 1;
        }).when(taskRecordMapper).insert(any(VideoTaskRecord.class));

        UploadMergeRequest req = new UploadMergeRequest();
        req.setFileMd5(SAMPLE_MD5);
        req.setFileName("lecture.mp4");
        req.setTotalSize(15728640L);
        req.setTotalChunks(3);

        UploadMergeResponse resp = uploadService.mergeChunks(req, 1001L);

        assertEquals(500L, resp.getTaskId());
        assertEquals(TaskStatusEnum.UPLOADED.getCode(), resp.getStatus());
        verify(minioService).composeChunks(SAMPLE_MD5, 3, "lecture.mp4");
        verify(minioService).calculateObjectMd5("videos/" + SAMPLE_MD5 + "/lecture.mp4");
        verify(redisTemplate).delete("upload:task:" + SAMPLE_MD5);
        verify(minioService).removeChunkObjects(SAMPLE_MD5, 3);
        verify(mergeLock).unlock();
    }

    @Test
    void shouldRejectMergedFileWhenFullMd5DoesNotMatchClientFingerprint() {
        String targetKey = "videos/" + SAMPLE_MD5 + "/lecture.mp4";
        when(redissonClient.getLock("dovideo:merge:" + SAMPLE_MD5)).thenReturn(mergeLock);
        when(mergeLock.tryLock()).thenReturn(true);
        when(mergeLock.isHeldByCurrentThread()).thenReturn(true);
        when(taskRecordMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("upload:task:" + SAMPLE_MD5)).thenReturn(3L);
        when(minioService.videoObjectKey(SAMPLE_MD5, "lecture.mp4")).thenReturn(targetKey);
        when(minioService.composeChunks(SAMPLE_MD5, 3, "lecture.mp4")).thenReturn(targetKey);
        when(minioService.calculateObjectMd5(targetKey)).thenReturn("0".repeat(32));
        doAnswer(inv -> {
            VideoTaskRecord record = inv.getArgument(0);
            record.setId(501L);
            return 1;
        }).when(taskRecordMapper).insert(any(VideoTaskRecord.class));

        UploadMergeRequest request = new UploadMergeRequest();
        request.setFileMd5(SAMPLE_MD5);
        request.setFileName("lecture.mp4");
        request.setTotalSize(15728640L);
        request.setTotalChunks(3);

        BusinessException error = assertThrows(
                BusinessException.class, () -> uploadService.mergeChunks(request, 1001L));

        assertEquals(ErrorCode.FILE_MD5_MISMATCH, error.getErrorCode());
        verify(minioService).removeObject(targetKey);
        verify(redisTemplate).delete("upload:task:" + SAMPLE_MD5);
        verify(minioService).removeChunkObjects(SAMPLE_MD5, 3);
        verify(taskRecordMapper).update(isNull(), any(UpdateWrapper.class));
        verify(taskRecordMapper, never()).updateById(any(VideoTaskRecord.class));
        verify(mergeLock).unlock();
    }

    @Test
    void shouldReconcileStuckMergingTaskWhenMinioObjectExists() {
        VideoTaskRecord stuck = VideoTaskRecord.builder()
                .id(600L)
                .userId(1001L)
                .videoMd5(SAMPLE_MD5)
                .totalChunks(3)
                .fileUrl("videos/" + SAMPLE_MD5 + "/lecture.mp4")
                .status(TaskStatusEnum.MERGING.getCode())
                .build();
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(stuck));
        when(minioService.objectExists("videos/" + SAMPLE_MD5 + "/lecture.mp4")).thenReturn(true);
        when(minioService.calculateObjectMd5("videos/" + SAMPLE_MD5 + "/lecture.mp4"))
                .thenReturn(SAMPLE_MD5);

        int repaired = reconciliationJob.reconcileStuckMergingTasks();

        assertEquals(1, repaired);
        verify(taskRecordMapper).update(isNull(), any(UpdateWrapper.class));
        verify(minioService).removeChunkObjects(SAMPLE_MD5, 3);
    }

    @Test
    void shouldReconcileStuckMergingTaskByDiscardingObjectWithMismatchedMd5() {
        String targetKey = "videos/" + SAMPLE_MD5 + "/lecture.mp4";
        VideoTaskRecord stuck = VideoTaskRecord.builder()
                .id(601L)
                .userId(1001L)
                .videoMd5(SAMPLE_MD5)
                .totalChunks(3)
                .fileUrl(targetKey)
                .status(TaskStatusEnum.MERGING.getCode())
                .build();
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(stuck));
        when(minioService.objectExists(targetKey)).thenReturn(true);
        when(minioService.calculateObjectMd5(targetKey)).thenReturn("0".repeat(32));

        int repaired = reconciliationJob.reconcileStuckMergingTasks();

        assertEquals(1, repaired);
        verify(minioService).removeObject(targetKey);
        verify(redisTemplate).delete("upload:task:" + SAMPLE_MD5);
        verify(minioService).removeChunkObjects(SAMPLE_MD5, 3);
        verify(taskRecordMapper).update(isNull(), any(UpdateWrapper.class));
    }
}
