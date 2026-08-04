package com.stcloud.auth.controller;

import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.service.AuthService;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证授权", description = "用户注册、登录、Token管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册")
    @Auditable(action = "REGISTER", targetType = "USER")
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @Operation(summary = "用户登录")
    @Auditable(action = "LOGIN", targetType = "USER")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        return Result.success(authService.login(request, ip));
    }

    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestParam String refreshToken) {
        return Result.success(authService.refreshToken(refreshToken));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<LoginResponse> me() {
        return Result.success(authService.getCurrentUserInfo());
    }

    @Operation(summary = "退出登录")
    @Auditable(action = "LOGOUT", targetType = "USER")
    @PostMapping("/logout")
    public Result<Void> logout() {
        // 客户端清除Token即可，服务端可选清除refresh token
        return Result.success();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }
}
