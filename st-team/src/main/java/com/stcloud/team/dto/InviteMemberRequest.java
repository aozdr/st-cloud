package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "邀请成员请求")
public class InviteMemberRequest {

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名")
    private String username;

    @Schema(description = "角色：0-管理员 1-编辑者 2-查看者，默认2")
    private Integer role = 2;
}
