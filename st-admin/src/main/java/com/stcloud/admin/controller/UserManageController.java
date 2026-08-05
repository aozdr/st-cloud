package com.stcloud.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.admin.dto.CreateUserRequest;
import com.stcloud.admin.dto.UpdateUserRequest;
import com.stcloud.admin.dto.UserManageVO;
import com.stcloud.admin.service.UserManageService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理", description = "管理员用户管理接口")
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:user:manage') or hasRole('ADMIN')")
public class UserManageController {

    private final UserManageService userManageService;

    @Operation(summary = "用户列表")
    @GetMapping("/list")
    public Result<IPage<UserManageVO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(userManageService.listUsers(page, size));
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{userId}")
    public Result<UserManageVO> getUser(@PathVariable Long userId) {
        return Result.success(userManageService.getUser(userId));
    }

    @Operation(summary = "创建用户")
    @Auditable(action = "CREATE_USER", targetType = "USER")
    @PostMapping
    public Result<UserManageVO> createUser(@RequestBody CreateUserRequest request) {
        return Result.success(userManageService.createUser(request));
    }
    @Operation(summary = "修改用户（禁用/配额/重置密码）")
    @Auditable(action = "UPDATE_USER", targetType = "USER")
    @PutMapping("/{userId}")
    public Result<Void> updateUser(@PathVariable Long userId, @RequestBody UpdateUserRequest request) {
        userManageService.updateUser(userId, request);
        return Result.success();
    }

    @Operation(summary = "删除用户")
    @Auditable(action = "DELETE_USER", targetType = "USER")
    @DeleteMapping("/{userId}")
    public Result<Void> deleteUser(@PathVariable Long userId) {
        userManageService.deleteUser(userId);
        return Result.success();
    }
}
