package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "给用户分配角色")
public class AssignRolesRequest {

    @Schema(description = "角色ID列表")
    private List<Long> roleIds;
}
