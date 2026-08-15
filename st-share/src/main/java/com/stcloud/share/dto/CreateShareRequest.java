package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "创建分享请求")
public class CreateShareRequest {

    @NotNull(message = "文件ID不能为空")
    @Schema(description = "分享的文件节点ID")
    private Long fileNodeId;

    @Schema(description = "分享类型：0-公开 1-私密(提取码)")
    private Integer shareType = 0;

    @Schema(description = "访问密码(私密类型时必填)")
    private String password;

    @Schema(description = "过期时间，NULL=永久")
    private LocalDateTime expireAt;

    @Schema(description = "权限：0-查看 1-下载 2-上传 3-编辑（兼容旧值，不传时按 permissions/默认权限解析）")
    private Integer permission;

    @Schema(description = "分享权限点JSON：{\"view\":true,\"download\":true}，不传时默认=用户有效权限（个人文件默认view+download）")
    private String permissions;

    @Schema(description = "允许下载/流式：0-禁止 1-允许（不传时与 permission 联动：仅查看默认禁止下载）")
    private Integer allowDownload = 1;

    @Schema(description = "下载次数限制，NULL=不限")
    private Integer downloadLimit;
}
