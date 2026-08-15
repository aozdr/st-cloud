package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 块级同步上传组装请求（迭代 5）
 * <p>
 * 客户端上传完缺失块后调用，服务端复制可复用块 + 合并 multipart + 更新文件节点 + 写块布局 + 发同步事件。
 */
@Data
@Schema(description = "块级同步上传组装请求")
public class BlockUploadRequest {

    @Schema(description = "文件节点ID")
    @NotNull
    private Long fileNodeId;

    @Schema(description = "S3 multipart uploadId（来自 block-check）")
    @NotNull
    private String s3UploadId;

    @Schema(description = "新版本对象存储路径（来自 block-check）")
    @NotNull
    private String storagePath;

    @Schema(description = "文件MD5（全文件）")
    @NotNull
    private String fileMd5;

    @Schema(description = "文件大小（字节）")
    @NotNull
    private Long fileSize;

    @Schema(description = "块大小（字节）")
    @NotNull
    private Long blockSize;

    @Schema(description = "总块数")
    @NotNull
    private Integer totalBlocks;

    @Schema(description = "全部分块哈希列表（服务端据此重新派生可复用块并写入新版本块布局）")
    @NotNull
    private List<BlockCheckRequest.BlockHash> blocks;
}
