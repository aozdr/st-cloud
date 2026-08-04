package com.stcloud.admin.controller;

import com.stcloud.admin.dto.PermissionVO;
import com.stcloud.admin.service.PermissionService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "权限管理", description = "权限定义查询接口")
@RestController
@RequestMapping("/api/admin/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @Operation(summary = "权限列表（全部）")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @GetMapping("/list")
    public Result<List<PermissionVO>> listAll() {
        return Result.success(permissionService.listAllPermissions());
    }

    @Operation(summary = "权限列表（按模块分组）")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @GetMapping("/grouped")
    public Result<Map<String, List<PermissionVO>>> listGrouped() {
        return Result.success(permissionService.listPermissionsByModule());
    }
}
