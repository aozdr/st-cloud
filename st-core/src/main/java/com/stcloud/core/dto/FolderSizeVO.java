package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件夹大小统计 VO：递归聚合子树总大小与文件/文件夹数量。
 */
@Data
@Schema(description = "文件夹大小统计")
public class FolderSizeVO {

    @Schema(description = "子树总大小（字节）")
    private Long size;

    @Schema(description = "文件总数")
    private Long fileCount;

    @Schema(description = "子文件夹总数")
    private Long folderCount;
}
