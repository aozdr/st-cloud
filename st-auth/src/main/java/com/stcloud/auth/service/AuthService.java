package com.stcloud.auth.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.entity.*;
import com.stcloud.auth.mapper.*;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysTenantMapper tenantMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "stcloud:refresh:";
    private static final Long DEFAULT_QUOTA = 10L * 1024 * 1024 * 1024; // 10GB

    /**
     * 用户注册
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        Long existingCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));
        if (existingCount > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        SysTenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<SysTenant>()
                        .eq(SysTenant::getTenantCode, "default")
                        .last("LIMIT 1"));
        if (tenant == null) {
            tenant = new SysTenant();
            tenant.setTenantName("默认租户");
            tenant.setTenantCode("default");
            tenant.setStatus(1);
            tenant.setDefaultQuota(DEFAULT_QUOTA);
            tenantMapper.insert(tenant);
        }

        TenantContext.setTenantId(tenant.getId());

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(BCrypt.hashpw(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setStorageUsed(0L);
        user.setStorageQuota(DEFAULT_QUOTA);
        userMapper.insert(user);

        // 分配默认 user 角色
        assignDefaultRole(user.getId(), tenant.getId());

        log.info("用户注册成功: username={}, userId={}, tenantId={}", user.getUsername(), user.getId(), tenant.getId());

        UserPermissions userPerms = loadUserPermissions(user);
        String token = JwtUtils.generateToken(user.getId(), tenant.getId(), user.getUsername(),
                false, userPerms.roles, userPerms.permissions);
        String refreshToken = JwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                30, TimeUnit.DAYS);

        return buildLoginResponse(token, refreshToken, user, false, userPerms);
    }

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request, String ip) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_INCORRECT);
        }

        if (user.getStatus() != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "账号已被禁用");
        }

        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        if (tenant == null || tenant.getStatus() != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "租户不可用");
        }

        // 设置租户上下文以查询角色权限
        TenantContext.setTenantId(user.getTenantId());

        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);

        boolean isAdmin = user.getIsAdmin() == 1;
        UserPermissions userPerms = loadUserPermissions(user);
        String token = JwtUtils.generateToken(user.getId(), tenant.getId(), user.getUsername(),
                isAdmin, userPerms.roles, userPerms.permissions);
        String refreshToken = JwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                30, TimeUnit.DAYS);

        log.info("用户登录成功: username={}, userId={}", user.getUsername(), user.getId());

        return buildLoginResponse(token, refreshToken, user, isAdmin, userPerms);
    }

    /**
     * 刷新Token
     */
    public LoginResponse refreshToken(String refreshToken) {
        if (!JwtUtils.validateToken(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_EXPIRED);
        }

        Long userId = JwtUtils.getUserId(refreshToken);
        String cachedToken = stringRedisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (cachedToken == null || !cachedToken.equals(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        TenantContext.setTenantId(user.getTenantId());

        boolean isAdmin = user.getIsAdmin() == 1;
        UserPermissions userPerms = loadUserPermissions(user);
        String newToken = JwtUtils.generateToken(user.getId(), tenant.getId(), user.getUsername(),
                isAdmin, userPerms.roles, userPerms.permissions);
        String newRefreshToken = JwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                newRefreshToken,
                30, TimeUnit.DAYS);

        return buildLoginResponse(newToken, newRefreshToken, user, isAdmin, userPerms);
    }

    /**
     * 获取当前用户信息
     */
    public LoginResponse getCurrentUserInfo() {
        UserContext.CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        SysUser user = userMapper.selectById(currentUser.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        UserPermissions userPerms = loadUserPermissions(user);
        boolean isAdmin = user.getIsAdmin() == 1;
        return buildLoginResponse(null, null, user, isAdmin, userPerms);
    }

    // ==================== 角色权限加载 ====================

    /**
     * 加载用户的角色和权限码
     */
    private UserPermissions loadUserPermissions(SysUser user) {
        TenantContext.setTenantId(user.getTenantId());

        // 超管旁路：is_admin=1 直接授予全部权限
        if (user.getIsAdmin() == 1) {
            List<SysPermission> allPerms = permissionMapper.selectList(null);
            List<String> permCodes = allPerms.stream()
                    .map(SysPermission::getPermissionCode)
                    .collect(Collectors.toList());
            return new UserPermissions(List.of("admin"), permCodes);
        }

        // 查询用户角色关联
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, user.getId()));

        if (userRoles.isEmpty()) {
            return new UserPermissions(List.of(), List.of());
        }

        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toList());

        // 查询启用的角色
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, 1));

        if (roles.isEmpty()) {
            return new UserPermissions(List.of(), List.of());
        }

        List<String> roleCodes = roles.stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
        List<Long> enabledRoleIds = roles.stream()
                .map(SysRole::getId)
                .collect(Collectors.toList());

        // 查询角色-权限关联
        List<SysRolePermission> rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, enabledRoleIds));

        if (rolePerms.isEmpty()) {
            return new UserPermissions(roleCodes, List.of());
        }

        // 查询权限码
        List<Long> permIds = rolePerms.stream()
                .map(SysRolePermission::getPermissionId)
                .distinct()
                .collect(Collectors.toList());
        List<SysPermission> perms = permissionMapper.selectBatchIds(permIds);
        List<String> permCodes = perms.stream()
                .map(SysPermission::getPermissionCode)
                .collect(Collectors.toList());

        return new UserPermissions(roleCodes, permCodes);
    }

    /**
     * 为新注册用户分配默认 user 角色
     */
    private void assignDefaultRole(Long userId, Long tenantId) {
        SysRole defaultRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, "user")
                        .eq(SysRole::getStatus, 1)
                        .last("LIMIT 1"));
        if (defaultRole != null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(defaultRole.getId());
            userRoleMapper.insert(userRole);
        }
    }

    private LoginResponse buildLoginResponse(String token, String refreshToken,
                                              SysUser user, boolean isAdmin, UserPermissions userPerms) {
        return LoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .isAdmin(isAdmin)
                .storageUsed(user.getStorageUsed())
                .storageQuota(user.getStorageQuota())
                .roles(userPerms.roles)
                .permissions(userPerms.permissions)
                .build();
    }

    /**
     * 用户权限加载结果
     */
    private record UserPermissions(List<String> roles, List<String> permissions) {}
}
