package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 块级复用查询响应
 */
@Data
@Schema(description = "块级复用查询响应")
public class BlockCheckResponse {

    @Schema(description = "云端文件是否存在（false 表示需全量上传）")
    private Boolean cloudExists;

    @Schema(description = "云端文件MD5（若与本地一致则无需块级同步）")
    private String cloudMd5;

    @Schema(description = "可复用块索引列表（云端已有且哈希一致的块）")
    private List<Integer> reusableBlocks;

    @Schema(description = "需上传块索引列表（云端缺失或哈希不一致的块）")
    private List<Integer> missingBlocks;

    @Schema(description = "块大小（字节）")
    private Long blockSize;
}