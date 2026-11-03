package com.dovideo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UploadMergeRequest {

    @NotBlank(message = "文件MD5不能为空")
    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "文件MD5必须为32位十六进制字符串")
    private String fileMd5;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @Min(value = 1, message = "文件大小必须大于0")
    private Long totalSize;

    @Min(value = 1, message = "总分片数必须大于等于1")
    private Integer totalChunks;
}
