package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "合并分片请求")
public class UploadMergeRequest {

    @Schema(description = "上传唯一标识")
    @NotBlank(message = "uploadId不能为空")
    private String uploadId;

    @Schema(description = "S3 multipart upload ID")
    @NotBlank(message = "s3UploadId不能为空")
    private String s3UploadId;

    @Schema(description = "文件节点ID（用于审计日志记录）")
    private Long fileId;
}
