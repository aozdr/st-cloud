package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 邀请成员请求
 * 改为按 userId 邀请（前端搜索选择用户后传入），避免手动输入用户名导致拼写错误
 */
@Data
@Schema(description = "邀请成员请求")
public class InviteMemberRequest {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "角色：0-管理员 1-编辑者 2-查看者，默认2")
    private Integer role = 2;
}