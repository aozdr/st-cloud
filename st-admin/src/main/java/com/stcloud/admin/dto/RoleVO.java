package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "角色展示对象")
public class RoleVO {

    @Schema(description = "角色ID")
    private Long id;

    @Schema(description = "角色编码")
    private String roleCode;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：0-禁用 1-启用")
    private Integer status;

    @Schema(description = "是否内置角色")
    private Boolean builtIn;

    @Schema(description = "权限列表")
    private List<PermissionVO> permissions;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
