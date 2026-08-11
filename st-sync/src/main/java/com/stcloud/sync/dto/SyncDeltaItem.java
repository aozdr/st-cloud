package com.stcloud.sync.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "同步变更项")
public class SyncDeltaItem {

    @Schema(description = "日志ID（游标）")
    private String logId;

    @Schema(description = "节点ID")
    private String nodeId;

    @Schema(description = "父节点ID")
    private String parentId;

    @Schema(description = "变更类型：CREATE/UPDATE/MOVE/RENAME/DELETE")
    private String changeType;

    @Schema(description = "相对同步根的路径")
    private String path;

    @Schema(description = "变更前相对路径（MOVE/RENAME）")
    private String oldPath;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "节点类型：0-文件夹 1-文件")
    private Integer nodeType;

    @Schema(description = "文件大小(字节)")
    private Long size;

    @Schema(description = "文件MD5")
    private String md5;

    @Schema(description = "后缀")
    private String suffix;

    @Schema(description = "状态：0-正常 1-回收站")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
