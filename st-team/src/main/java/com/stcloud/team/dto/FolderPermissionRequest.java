package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "设置文件夹权限请求")
public class FolderPermissionRequest {
    @NotNull(message = "权限规则不能为空")
    @Schema(description = "权限规则列表") private List<PermissionRule> rules;

    @Data
    @Schema(description = "单条权限规则")
    public static class PermissionRule {
        @Schema(description = "授权对象类型：all(全体,管理员除外)/role/member") private String subjectType;
        @Schema(description = "角色值或用户ID") private Long subjectId;
        @Schema(description = "权限：-1-无权限 0-管理 1-编辑 2-查看") private Integer permission;
        @Schema(description = "权限点JSON（优先）：{\"view\":true,\"upload\":true,...}") private String permissions;
    }
}
