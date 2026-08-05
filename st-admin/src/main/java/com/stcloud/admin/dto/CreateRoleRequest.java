package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建/编辑角色请求")
public class CreateRoleRequest {

    @Schema(description = "角色编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：0-禁用 1-启用")
    private Integer status;

    @Schema(description = "数据范围：1-本人 2-租户 3-全部")
    private Integer dataScope;
}
