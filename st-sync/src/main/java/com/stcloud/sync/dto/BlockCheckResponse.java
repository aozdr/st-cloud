package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 块级同步检查响应（迭代 5）
 * <p>
 * 服务端初始化新版本 multipart 上传，返回可复用块（服务端 UploadPartCopy）与缺失块（客户端上传预签名URL）。
 */
@Data
@Schema(description = "块级同步检查响应")
public class BlockCheckResponse {

    @Schema(description = "S3 multipart uploadId（用于后续 block-upload）")
    private String s3UploadId;

    @Schema(description = "新版本对象存储路径")
    private String storagePath;

    @Schema(description = "可复用块列表（服务端从旧版本对象复制）")
    private List<ReusableBlock> reusableBlocks;

    @Schema(description = "缺失块预签名URL列表（客户端直传S3）")
    private List<PresignedBlock> missingBlocks;

    @Data
    @Schema(description = "可复用块")
    public static class ReusableBlock {
        private Integer blockIndex;
        private String sourceKey;
        private Long rangeStart;
        private Long rangeEnd;
    }

    @Data
    @Schema(description = "缺失块预签名信息")
    public static class PresignedBlock {
        private Integer blockIndex;
        private String presignedUrl;
    }
}
