package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分享访问结果")
public class ShareAccessVO {

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件类型：0-文件夹 1-文件")
    private Integer fileType;

    @Schema(description = "文件后缀")
    private String suffix;

    @Schema(description = "文件大小(字节)")
    private Long size;

    @Schema(description = "权限：0-查看 1-下载 2-上传 3-编辑")
    private Integer permission;

    @Schema(description = "分享文件节点ID")
    private Long fileNodeId;

    @Schema(description = "分享类型：0-公开 1-私密")
    private Integer shareType;
}
