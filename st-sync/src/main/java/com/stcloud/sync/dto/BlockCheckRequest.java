package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 块级增量同步：复用查询请求
 * <p>
 * 客户端传入文件节点 ID 和本地文件的块哈希列表，
 * 服务端对比云端版本的块哈希，返回可复用块索引和需上传的块索引。
 */
@Data
@Schema(description = "块级复用查询")
public class BlockCheckRequest {

    @Schema(description = "文件节点ID")
    @NotNull(message = "文件节点ID不能为空")
    private Long fileNodeId;

    @Schema(description = "块哈希列表（按块序号排列）")
    @NotNull(message = "块哈希列表不能为空")
    private List<String> blockHashes;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;
}