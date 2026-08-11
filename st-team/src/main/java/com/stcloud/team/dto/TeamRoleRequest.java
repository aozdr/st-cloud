package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建/编辑角色请求")
public class TeamRoleRequest {
    @NotBlank(message = "角色名称不能为空")
    @Schema(description = "角色名称") private String name;
    @NotBlank(message = "权限不能为空")
    @Schema(description = "权限JSON") private String permissions;
}