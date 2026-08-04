package com.stcloud.admin.controller;

import com.stcloud.admin.dto.AssignPermissionsRequest;
import com.stcloud.admin.dto.AssignRolesRequest;
import com.stcloud.admin.dto.CreateRoleRequest;
import com.stcloud.admin.dto.RoleVO;
import com.stcloud.admin.service.RoleService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "角色管理", description = "角色与权限管理接口")
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色列表")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @GetMapping("/list")
    public Result<List<RoleVO>> listRoles() {
        return Result.success(roleService.listRoles());
    }

    @Operation(summary = "角色详情（含权限列表）")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @GetMapping("/{roleId}")
    public Result<RoleVO> getRole(@PathVariable Long roleId) {
        return Result.success(roleService.getRole(roleId));
    }

    @Operation(summary = "创建角色")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @PostMapping
    public Result<RoleVO> createRole(@RequestBody CreateRoleRequest request) {
        return Result.success(roleService.createRole(request));
    }

    @Operation(summary = "编辑角色")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @PutMapping("/{roleId}")
    public Result<RoleVO> updateRole(@PathVariable Long roleId, @RequestBody CreateRoleRequest request) {
        return Result.success(roleService.updateRole(roleId, request));
    }

    @Operation(summary = "删除角色（内置角色不可删除）")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @DeleteMapping("/{roleId}")
    public Result<Void> deleteRole(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
        return Result.success();
    }

    @Operation(summary = "给角色分配权限")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @PutMapping("/{roleId}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long roleId,
                                           @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(roleId, request);
        return Result.success();
    }

    @Operation(summary = "获取用户的角色列表")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @GetMapping("/user/{userId}")
    public Result<List<RoleVO>> getUserRoles(@PathVariable Long userId) {
        return Result.success(roleService.getUserRoles(userId));
    }

    @Operation(summary = "给用户分配角色")
    @PreAuthorize("hasAuthority('admin:role:manage') or hasRole('ADMIN')")
    @PutMapping("/user/{userId}")
    public Result<Void> assignRolesToUser(@PathVariable Long userId,
                                           @RequestBody AssignRolesRequest request) {
        roleService.assignRolesToUser(userId, request.getRoleIds());
        return Result.success();
    }
}
