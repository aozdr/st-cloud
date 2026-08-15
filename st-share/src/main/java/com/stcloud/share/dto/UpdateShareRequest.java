package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "修改分享请求")
public class UpdateShareRequest {

    @Schema(description = "分享类型：0-公开 1-私密")
    private Integer shareType;

    @Schema(description = "访问密码")
    private String password;

    @Schema(description = "过期时间")
    private LocalDateTime expireAt;

    @Schema(description = "是否清除过期时间（设为永久）")
    private Boolean clearExpireAt;

    @Schema(description = "权限")
    private Integer permission;

    @Schema(description = "分享权限点JSON：{\"view\":true,\"download\":true}；更新时与 allow_download 联动")
    private String permissions;

    @Schema(description = "允许下载/流式：0-禁止 1-允许")
    private Integer allowDownload;

    @Schema(description = "下载次数限制")
    private Integer downloadLimit;

    @Schema(description = "状态：0-已取消 1-有效")
    private Integer status;
}
