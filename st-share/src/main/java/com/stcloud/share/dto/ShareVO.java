package com.stcloud.share.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "分享信息")
public class ShareVO {

    @Schema(description = "分享ID")
    private Long id;

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "文件节点ID")
    private Long fileNodeId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "分享类型：0-公开 1-私密")
    private Integer shareType;

    @Schema(description = "提取码(仅私密分享,明文)")
    private String password;

    @Schema(description = "过期时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    @Schema(description = "权限")
    private Integer permission;

    @Schema(description = "分享权限点JSON：{\"view\":true,\"download\":true}")
    private String permissions;

    @Schema(description = "允许下载/流式：0-禁止 1-允许")
    private Integer allowDownload;

    @Schema(description = "下载次数限制")
    private Integer downloadLimit;

    @Schema(description = "已下载次数")
    private Integer downloadCount;

    @Schema(description = "访问次数")
    private Integer viewCount;

    @Schema(description = "状态：0-已取消 1-有效")
    private Integer status;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
