package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@Schema(description = "断点续传状态响应")
public class UploadStatusResponse {

    @Schema(description = "上传唯一标识")
    private String uploadId;

    @Schema(description = "已上传分片序号列表")
    private List<Integer> uploadedChunkIndexes;

    @Schema(description = "未上传分片的新鲜预签名URL（key=分片序号）")
    private Map<Integer, String> presignedUrls;
}
