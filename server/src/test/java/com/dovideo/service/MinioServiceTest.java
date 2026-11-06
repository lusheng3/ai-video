package com.dovideo.service;

import com.dovideo.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioServiceTest {

    @Mock
    private MinioClient minioClient;

    private AppProperties appProperties;
    private MinioService minioService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        minioService = new MinioService(minioClient, appProperties);
    }

    @Test
    void shouldCreatePrivateBucketAndConfigure1DayLifecycleForTempChunks() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        minioService.ensureBucketAndLifecycle();

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        ArgumentCaptor<SetBucketLifecycleArgs> lifecycleCaptor = ArgumentCaptor.forClass(SetBucketLifecycleArgs.class);
        verify(minioClient).setBucketLifecycle(lifecycleCaptor.capture());

        SetBucketLifecycleArgs captured = lifecycleCaptor.getValue();
        assertEquals("dovideo-private", captured.bucket());
        assertEquals(1, captured.config().rules().size());
        assertEquals("temp_chunks/", captured.config().rules().get(0).filter().prefix());
        assertEquals(1, captured.config().rules().get(0).expiration().days());
    }

    @Test
    void shouldComposeChunksInStrictIndexOrder() throws Exception {
        String fileMd5 = "d41d8cd98f00b204e9800998ecf8427e";
        String targetKey = minioService.composeChunks(fileMd5, 3, "course.mp4");

        assertEquals("videos/d41d8cd98f00b204e9800998ecf8427e/course.mp4", targetKey);

        ArgumentCaptor<ComposeObjectArgs> composeCaptor = ArgumentCaptor.forClass(ComposeObjectArgs.class);
        verify(minioClient).composeObject(composeCaptor.capture());
        ComposeObjectArgs args = composeCaptor.getValue();
        assertEquals(3, args.sources().size());
        assertEquals("temp_chunks/" + fileMd5 + "/0", args.sources().get(0).object());
        assertEquals("temp_chunks/" + fileMd5 + "/1", args.sources().get(1).object());
        assertEquals("temp_chunks/" + fileMd5 + "/2", args.sources().get(2).object());
    }

    @Test
    void shouldGenerate5MinutePresignedUrlAndCheckObjectExists() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://127.0.0.1:9000/dovideo-private/videos/md5/course.mp4?X-Amz-Expires=300&X-Amz-Signature=abc");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));

        String url = minioService.getPresignedPlayUrl("videos/md5/course.mp4");
        boolean exists = minioService.objectExists("videos/md5/course.mp4");

        assertTrue(url.contains("X-Amz-Expires=300"));
        assertTrue(exists);
    }

    @Test
    void shouldCalculateFullObjectMd5ByStreamingContent() {
        String objectKey = "videos/md5/course.mp4";
        MinioService service = spy(minioService);
        doReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)))
                .when(service).getObjectStream(objectKey);

        String md5 = service.calculateObjectMd5(objectKey);

        assertEquals("5d41402abc4b2a76b9719d911017c592", md5);
    }
}
