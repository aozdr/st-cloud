package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "创建/编辑限速规则请求")
public class CreateSpeedLimitRequest {

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    @Schema(description = "限制范围:0-按用户 1-按角色", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "限制范围不能为空")
    @Min(0)
    private Integer scope;

    @Schema(description = "目标ID:用户ID或角色ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    @Schema(description = "匹配标识:角色编码(role) / 用户名(user)")
    private String targetCode;

    @Schema(description = "展示名:昵称 / 角色名")
    private String targetName;

    @Schema(description = "上传限速 KB/s,0=不限速")
    @Min(0)
    private Integer uploadSpeedLimit;

    @Schema(description = "下载限速 KB/s,0=不限速")
    @Min(0)
    private Integer downloadSpeedLimit;

    @Schema(description = "0-禁用 1-启用")
    private Integer enabled;

    @Schema(description = "描述")
    private String description;
}