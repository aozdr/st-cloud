package com.stcloud.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "文件/文件夹信息")
public class FileNodeVO {

    @Schema(description = "节点ID")
    private Long id;

    @Schema(description = "父文件夹ID")
    private Long parentId;

    @Schema(description = "节点类型：0-文件夹 1-文件")
    private Integer nodeType;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "完整路径")
    private String path;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @Schema(description = "文件后缀")
    private String suffix;

    @Schema(description = "MIME类型")
    private String contentType;

    @Schema(description = "状态：0-正常 1-回收站")
    private Integer status;

    @Schema(description = "缩略图路径")
    private String thumbnailPath;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
