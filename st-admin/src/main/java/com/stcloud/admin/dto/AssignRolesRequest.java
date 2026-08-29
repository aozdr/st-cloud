package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "给用户分配角色")
public class AssignRolesRequest {

    @Schema(description = "角色ID列表（字符串承载，防 snowflake 精度丢失）")
    private List<String> roleIds;
}
