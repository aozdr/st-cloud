package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "访问分享请求")
public class ShareAccessRequest {

    @NotBlank(message = "分享码不能为空")
    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "访问密码(私密分享必填)")
    private String password;

    @Schema(description = "验证码ID(失败达阈值后必填)")
    private String captchaId;

    @Schema(description = "验证码内容(失败达阈值后必填)")
    private String captchaCode;
}
