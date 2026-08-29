package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "保存分享内容到云盘请求（需登录）")
public class SaveShareRequest {

    @NotBlank(message = "分享码不能为空")
    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "访问密码(私密分享必填)")
    private String password;

    @Schema(description = "验证码ID(失败达阈值后必填)")
    private String captchaId;

    @Schema(description = "验证码内容(失败达阈值后必填)")
    private String captchaCode;

    @NotNull(message = "保存目标文件夹不能为空")
    @Schema(description = "保存到的目标文件夹ID（0 表示我的云盘根目录）")
    private Long targetParentId;
}
