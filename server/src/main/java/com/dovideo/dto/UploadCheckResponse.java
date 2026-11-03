package com.dovideo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 分片上传预检 (/api/upload/check) 响应体：
 * 同时支撑“秒传”、“断点续传（返回已传分片序号集合）”与“新上传任务”三种场景。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadCheckResponse {

    /** 是否触发秒传（true表示服务器已有该MD5完整视频，前端无需上传任何分片） */
    private boolean deduplicated;

    /** 秒传命中或已合并完成时返回的任务ID */
    private Long taskId;

    /** MinIO存储对象路径 */
    private String fileUrl;

    /** 已成功上传到MinIO并记入Redis Set的分片序号集合（如 [0, 1, 4]） */
    private Set<Integer> uploadedChunks;
}
