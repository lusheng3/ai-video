package com.dovideo.controller;

import com.dovideo.common.Result;
import com.dovideo.dto.UploadCheckResponse;
import com.dovideo.dto.UploadMergeRequest;
import com.dovideo.dto.UploadMergeResponse;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.service.AuthService;
import com.dovideo.service.UploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 分片上传、断点续传、合并与媒体查询控制层（Controller层）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 1. 上传前预检接口（秒传判断 + 返回Redis Set中已上传的分片序号集合用于断点续传）
     */
    @GetMapping("/upload/check")
    public Result<UploadCheckResponse> checkUpload(
            @RequestParam String fileMd5,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) Long totalSize,
            @RequestParam(required = false) Integer totalChunks,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        return Result.ok(uploadService.checkUpload(fileMd5, fileName, totalSize, totalChunks, userId));
    }

    /**
     * 2. 分片上传接口（先校验分片MD5并落盘MinIO，成功后再写入Redis Set）
     */
    @PostMapping("/upload/chunk")
    public Result<Void> uploadChunk(
            @RequestParam String fileMd5,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam(required = false) String chunkMd5,
            @RequestParam("file") MultipartFile file) {
        uploadService.uploadChunk(fileMd5, chunkIndex, totalChunks, chunkMd5, file);
        return Result.ok();
    }

    /**
     * 3. 前端确认所有分片上传完毕后主动调用的合并接口（Redisson合并锁 + MinIO composeObject）
     */
    @PostMapping("/upload/merge")
    public Result<UploadMergeResponse> mergeChunks(
            @Valid @RequestBody UploadMergeRequest request,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        return Result.ok(uploadService.mergeChunks(request, userId));
    }

    /**
     * 4. 获取私有Bucket视频的5分钟有效预签名播放URL（防盗链）
     */
    @GetMapping("/media/play-url/{taskId}")
    public Result<Map<String, String>> getPlayUrl(
            @PathVariable Long taskId,
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        String playUrl = uploadService.getPlayUrl(taskId, userId);
        return Result.ok(Map.of("playUrl", playUrl));
    }

    /**
     * 5. 查询当前用户的历史视频任务列表
     */
    @GetMapping("/media/list")
    public Result<List<VideoTaskRecord>> listUserTasks(
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId) {
        return Result.ok(uploadService.listUserTasks(userId));
    }
}
