package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "分片上传初始化响应")
public class UploadInitResponse {

    @Schema(description = "上传唯一标识")
    private String uploadId;

    @Schema(description = "S3 multipart upload ID")
    private String s3UploadId;

    @Schema(description = "文件节点ID")
    private Long fileId;

    @Schema(description = "各分片预签名URL")
    private List<String> presignedUrls;
}
