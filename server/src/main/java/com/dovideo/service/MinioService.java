package com.dovideo.service;

import com.dovideo.common.ErrorCode;
import com.dovideo.config.AppProperties;
import com.dovideo.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 分布式对象存储服务（对应简历亮点4 & 亮点2）：
 * 1. 服务无状态化：所有视频分片与成品文件下沉至 MinIO 私有 Bucket（默认 Private 权限）。
 * 2. 生命周期规则（Lifecycle）：自动为 temp_chunks/ 前缀配置 1 天过期物理删除，清理僵尸分片。
 * 3. 服务端原子合并（composeObject）：在 MinIO 存储引擎内部直接将分片拼接为完整视频，免去后端磁盘中转。
 * 4. 预签名 URL（Pre-signed URL）：签发 5 分钟有效期的临时带签名访问链接，防止资源被盗链。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;
    private final AppProperties appProperties;

    /**
     * 容器启动后尝试初始化私有 Bucket 与 temp_chunks/ 生命周期自动清理规则
     */
    @PostConstruct
    public void initBucketAndLifecycleSafely() {
        try {
            ensureBucketAndLifecycle();
        } catch (Exception ex) {
            log.warn("minio_init_skipped_at_startup reason={}", ex.getMessage());
        }
    }

    /**
     * 确保私有 Bucket 存在，并挂载 temp_chunks/ 24小时(1天)自动删除的 Lifecycle 规则
     */
    public void ensureBucketAndLifecycle() throws Exception {
        AppProperties.MinioProps props = appProperties.getMinio();
        String bucket = props.getBucketName();

        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            // 新建的 Bucket 默认即为 Private（私有）权限，禁止未签名裸 URL 访问
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("minio_private_bucket_created bucket={}", bucket);
        }

        // 配置 Lifecycle 规则：Filter Prefix = temp_chunks/，Expiry = 1 Day，Action = Delete
        LifecycleRule tempChunkExpireRule = new LifecycleRule(
                Status.ENABLED,
                null,
                new Expiration((ZonedDateTime) null, props.getLifecycleExpireDays(), null),
                new RuleFilter(props.getTempChunkPrefix()),
                "expire-orphan-temp-chunks-1d",
                null,
                null,
                null
        );
        LifecycleConfiguration lifecycleConfig = new LifecycleConfiguration(List.of(tempChunkExpireRule));
        minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                .bucket(bucket)
                .config(lifecycleConfig)
                .build());
        log.info("minio_lifecycle_configured bucket={} prefix={} expireDays={}",
                bucket, props.getTempChunkPrefix(), props.getLifecycleExpireDays());
    }

    /**
     * 上传单个分片至 MinIO 临时目录：temp_chunks/{fileMd5}/{chunkIndex}
     */
    public String putChunkObject(String fileMd5, int chunkIndex, InputStream inputStream, long size) {
        String bucket = appProperties.getMinio().getBucketName();
        String objectKey = chunkObjectKey(fileMd5, chunkIndex);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType("application/octet-stream")
                    .build());
            return objectKey;
        } catch (Exception ex) {
            log.error("minio_put_chunk_failed fileMd5={} chunkIndex={}", fileMd5, chunkIndex, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "分片写入MinIO失败，请重试", ex);
        }
    }

    /**
     * 调用 MinIO composeObject API 在存储引擎内部将所有分片原子合并为完整视频文件
     */
    public String composeChunks(String fileMd5, int totalChunks, String fileName) {
        String bucket = appProperties.getMinio().getBucketName();
        String targetObjectKey = videoObjectKey(fileMd5, fileName);

        List<ComposeSource> sources = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            sources.add(ComposeSource.builder()
                    .bucket(bucket)
                    .object(chunkObjectKey(fileMd5, i))
                    .build());
        }

        try {
            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetObjectKey)
                    .sources(sources)
                    .build());
            log.info("minio_compose_object_success fileMd5={} totalChunks={} target={}",
                    fileMd5, totalChunks, targetObjectKey);
            return targetObjectKey;
        } catch (Exception ex) {
            log.error("minio_compose_object_failed fileMd5={} totalChunks={}", fileMd5, totalChunks, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "MinIO分片合并失败，请稍后重试", ex);
        }
    }

    /**
     * 生成 5 分钟有效期的 Pre-signed URL（预签名临时访问链接），防止私有 Bucket 资源被盗链
     */
    public String getPresignedPlayUrl(String objectKey) {
        AppProperties.MinioProps props = appProperties.getMinio();
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.getBucketName())
                    .object(objectKey)
                    .expiry(props.getPresignExpiryMinutes(), TimeUnit.MINUTES)
                    .build());
        } catch (Exception ex) {
            log.error("minio_presign_url_failed objectKey={}", objectKey, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "生成预签名播放链接失败", ex);
        }
    }

    /**
     * 检查目标对象是否已在 MinIO 中存在（供后台合并状态校准任务核实）
     */
    public boolean objectExists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(appProperties.getMinio().getBucketName())
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 获取 MinIO 对象输入流（供消费者下载提取音频）
     */
    public InputStream getObjectStream(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(appProperties.getMinio().getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "读取MinIO视频文件流失败", ex);
        }
    }

    /**
     * 合并成功后主动清理临时分片（异常未清理的分片由 MinIO Lifecycle 1天规则兜底物理删除）
     */
    public void removeChunkObjects(String fileMd5, int totalChunks) {
        String bucket = appProperties.getMinio().getBucketName();
        for (int i = 0; i < totalChunks; i++) {
            String chunkKey = chunkObjectKey(fileMd5, i);
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(chunkKey)
                        .build());
            } catch (Exception ex) {
                log.warn("minio_remove_chunk_ignored chunkKey={} reason={}", chunkKey, ex.getMessage());
            }
        }
    }

    public String chunkObjectKey(String fileMd5, int chunkIndex) {
        return appProperties.getMinio().getTempChunkPrefix() + fileMd5 + "/" + chunkIndex;
    }

    public String videoObjectKey(String fileMd5, String fileName) {
        String safeName = (fileName == null || fileName.isBlank())
                ? "video.mp4"
                : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return appProperties.getMinio().getVideoPrefix() + fileMd5 + "/" + safeName;
    }
}
