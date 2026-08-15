package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 块级同步检查请求（迭代 5）
 * <p>
 * 客户端发送本地文件分块哈希列表，服务端对比当前版本块布局，返回可复用块与缺失块。
 */
@Data
@Schema(description = "块级同步检查请求")
public class BlockCheckRequest {

    @Schema(description = "文件节点ID（已有文件，替换更新）")
    @NotNull
    private Long fileNodeId;

    @Schema(description = "文件MD5（全文件）")
    private String fileMd5;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "块大小（字节，固定5MB）")
    @NotNull
    private Long blockSize;

    @Schema(description = "本地分块列表")
    @NotNull
    private List<BlockHash> blocks;

    @Data
    @Schema(description = "分块哈希")
    public static class BlockHash {
        @Schema(description = "块序号（0-based）")
        private Integer index;
        @Schema(description = "块MD5")
        private String md5;
        @Schema(description = "块大小（字节）")
        private Long size;
    }
}
