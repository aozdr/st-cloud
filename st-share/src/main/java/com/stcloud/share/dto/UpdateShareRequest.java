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

    @Schema(description = "权限")
    private Integer permission;

    @Schema(description = "下载次数限制")
    private Integer downloadLimit;

    @Schema(description = "状态：0-已取消 1-有效")
    private Integer status;
}
