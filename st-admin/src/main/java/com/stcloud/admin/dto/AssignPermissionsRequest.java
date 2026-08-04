package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "给角色分配权限")
public class AssignPermissionsRequest {

    @Schema(description = "权限ID列表")
    private List<Long> permissionIds;
}
