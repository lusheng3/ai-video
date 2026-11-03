package com.dovideo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadMergeResponse {

    private Long taskId;
    private String videoMd5;
    private String fileUrl;
    private Integer status;
}
