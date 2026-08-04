package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "权限展示对象")
public class PermissionVO {

    @Schema(description = "权限ID")
    private Long id;

    @Schema(description = "权限编码")
    private String permissionCode;

    @Schema(description = "权限名称")
    private String permissionName;

    @Schema(description = "所属模块")
    private String module;

    @Schema(description = "描述")
    private String description;
}
