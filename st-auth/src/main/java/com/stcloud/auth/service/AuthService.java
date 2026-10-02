package com.stcloud.auth.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.entity.*;
import com.stcloud.auth.enums.TenantStatus;
import com.stcloud.auth.enums.UserStatus;
import com.stcloud.auth.mapper.*;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
    private final JwtUtils jwtUtils;
    private final UserSecurityService userSecurityService;
    private final PlatformTransactionManager transactionManager;

    private static final String REFRESH_TOKEN_PREFIX = "stcloud:refresh:";
    private static final Long DEFAULT_QUOTA = 10L * 1024 * 1024 * 1024; // 10GB

    /**
     * 用户注册
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LoginResponse register(RegisterRequest request) {
        Long tenantId = TenantContext.getTenantIdOrNull();
        // 公开注册沿用默认租户入口；已有上下文的调用保持原租户选择。
        return inTenant(tenantId != null ? tenantId : 1L, () -> registerInTenant(request));
    }

    private LoginResponse registerInTenant(RegisterRequest request) {
        // 密码哈希与 Redis 不进入数据库写事务；用户及默认角色提交后才能签发会话。
        String passwordHash = BCrypt.hashpw(request.getPassword());
        TransactionTemplate registration = new TransactionTemplate(transactionManager);
        registration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        registration.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        SysUser registered = registration.execute(status -> registerUser(request, passwordHash));

        // 独立只读快照保证用户版本、租户及权限来自同一提交视图，不拼接写事务的旧实体。
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        UserSnapshot current = snapshot.execute(status -> {
            SysUser user = userMapper.selectById(registered.getId());
            if (user == null || !Integer.valueOf(UserStatus.NORMAL.getCode()).equals(user.getStatus())) {
                throw new BusinessException(ResultCode.USER_NOT_FOUND);
            }
            SysTenant tenant = tenantMapper.selectById(user.getTenantId());
            if (tenant == null || !Integer.valueOf(TenantStatus.NORMAL.getCode()).equals(tenant.getStatus())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "租户不可用");
            }
            return new UserSnapshot(user, loadUserPermissions(user));
        });
        SysUser user = current.user();
        UserPermissions userPerms = current.permissions();
        if (!userSecurityService.isCurrent(user.getId(), user.getTenantId(), user.getSecurityVersion())) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        String token = jwtUtils.generateToken(user.getId(), user.getTenantId(), user.getUsername(),
                userPerms.roles, userPerms.permissions, userPerms.dataScope, user.getSecurityVersion());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getTenantId(), user.getUsername(), user.getSecurityVersion());
        stringRedisTemplate.opsForValue().set(REFRESH_TOKEN_PREFIX + user.getId(), refreshToken, 30, TimeUnit.DAYS);
        return buildLoginResponse(token, refreshToken, user, userPerms);
    }

    private SysUser registerUser(RegisterRequest request, String passwordHash) {
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
            // 新建默认租户状态正常
            tenant.setStatus(TenantStatus.NORMAL.getCode());
            tenant.setDefaultQuota(DEFAULT_QUOTA);
            tenantMapper.insert(tenant);
        }

        TenantContext.setTenantId(tenant.getId());
        userSecurityService.lockTenant(tenant.getId());
        // 等待安全写锁期间租户可能被禁用；RC 锁后重读才能使用最新决策状态。
        tenant = tenantMapper.selectById(tenant.getId());
        if (tenant == null || !Integer.valueOf(TenantStatus.NORMAL.getCode()).equals(tenant.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "租户不可用");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())) > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordHash);
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        // 新注册用户默认正常
        user.setStatus(UserStatus.NORMAL.getCode());
        user.setSecurityVersion(0L);
        user.setStorageUsed(0L);
        user.setStorageQuota(DEFAULT_QUOTA);
        userMapper.insert(user);

        // 分配默认 user 角色
        assignDefaultRole(user.getId(), tenant.getId());

        log.info("用户注册成功: username={}, userId={}, tenantId={}", user.getUsername(), user.getId(), tenant.getId());

        return user;
    }

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request, String ip) {
        Long tenantId = TenantContext.getTenantIdOrNull();
        // 无令牌登录显式使用现有默认租户，不能依赖数据库拦截器隐式兜底。
        return inTenant(tenantId != null ? tenantId : 1L, () -> loginInTenant(request, ip));
    }

    private LoginResponse loginInTenant(LoginRequest request, String ip) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_INCORRECT);
        }

        if (user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "账号已被禁用");
        }

        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        if (tenant == null || tenant.getStatus() != TenantStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "租户不可用");
        }

        // 设置租户上下文以查询角色权限
        TenantContext.setTenantId(user.getTenantId());

        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getLastLoginAt, LocalDateTime.now())
                .set(SysUser::getLastLoginIp, ip));

        UserPermissions userPerms = loadUserPermissions(user);
        String token = jwtUtils.generateToken(user.getId(), tenant.getId(), user.getUsername(),
                userPerms.roles, userPerms.permissions, userPerms.dataScope, user.getSecurityVersion());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), tenant.getId(), user.getUsername(), user.getSecurityVersion());

        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                30, TimeUnit.DAYS);

        log.info("用户登录成功: username={}, userId={}", user.getUsername(), user.getId());

        return buildLoginResponse(token, refreshToken, user, userPerms);
    }

    /**
     * 刷新Token
     */
    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtUtils.validateToken(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_EXPIRED);
        }

        io.jsonwebtoken.Claims claims = jwtUtils.parseToken(refreshToken);
        if (!"refresh".equals(claims.get("type"))
                || !userSecurityService.isCurrent(claims.get("userId"), claims.get("tenantId"), claims.get("securityVersion"))) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        // 仅在签名、用途和安全版本都通过后使用令牌租户，跨请求刷新不再落入默认租户1。
        Long tenantId = userSecurityService.exactNonNegativeLong(claims.get("tenantId"));
        return inTenant(tenantId, () -> refreshInTenant(refreshToken, claims));
    }

    private LoginResponse refreshInTenant(String refreshToken, io.jsonwebtoken.Claims claims) {

        Long userId = jwtUtils.getUserId(refreshToken);
        String cachedToken = stringRedisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (cachedToken == null || !cachedToken.equals(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        Long presentedVersion = userSecurityService.exactNonNegativeLong(claims.get("securityVersion"));
        Long presentedTenantId = userSecurityService.exactNonNegativeLong(claims.get("tenantId"));
        if (!java.util.Objects.equals(user.getSecurityVersion(), presentedVersion)
                || !java.util.Objects.equals(user.getTenantId(), presentedTenantId)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        TenantContext.setTenantId(user.getTenantId());

        UserPermissions userPerms = loadUserPermissions(user);
        // 权限读取期间若角色或用户状态已提交变更，旧 Refresh 不能继承新的安全版本。
        if (!userSecurityService.isCurrent(claims.get("userId"), claims.get("tenantId"), claims.get("securityVersion"))) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        String newToken = jwtUtils.generateToken(user.getId(), tenant.getId(), user.getUsername(),
                userPerms.roles, userPerms.permissions, userPerms.dataScope, user.getSecurityVersion());
        String newRefreshToken = jwtUtils.generateRefreshToken(user.getId(), tenant.getId(), user.getUsername(), user.getSecurityVersion());

        // 仅当旧 refresh 仍是 Redis 当前值时替换；并发刷新或撤销不能被覆盖。
        DefaultRedisScript<Long> cas = new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 else return 0 end", Long.class);
        Long replaced = stringRedisTemplate.execute(cas, Collections.singletonList(REFRESH_TOKEN_PREFIX + user.getId()),
                refreshToken, newRefreshToken, String.valueOf(TimeUnit.DAYS.toSeconds(30)));
        if (!Long.valueOf(1L).equals(replaced)) throw new BusinessException(ResultCode.TOKEN_INVALID);

        return buildLoginResponse(newToken, newRefreshToken, user, userPerms);
    }

    /** 临时认证上下文只覆盖当前操作，失败与成功都精确恢复原始租户，避免线程复用串租户。 */
    private <T> T inTenant(Long tenantId, Supplier<T> action) {
        Long previousTenantId = TenantContext.getTenantIdOrNull();
        try {
            TenantContext.setTenantId(tenantId);
            return action.get();
        } finally {
            TenantContext.setTenantId(previousTenantId);
        }
    }

    /**
     * 吊销用户 refresh token（禁用/重置密码时调用），使其无法刷新 access token
     */
    public void revokeRefreshToken(Long userId) {
        if (userId != null) {
            stringRedisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
        }
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
        return buildLoginResponse(null, null, user, userPerms);
    }

    // ==================== 角色权限加载 ====================

    /**
     * 加载用户的角色和权限码
     */
    private UserPermissions loadUserPermissions(SysUser user) {
        TenantContext.setTenantId(user.getTenantId());

        // 查询用户角色关联
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, user.getId()));

        if (userRoles.isEmpty()) {
            return new UserPermissions(List.of(), List.of(), 1);
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
            return new UserPermissions(List.of(), List.of(), 1);
        }

        List<String> roleCodes = roles.stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
        List<Long> enabledRoleIds = roles.stream()
                .map(SysRole::getId)
                .collect(Collectors.toList());

        // 数据范围取用户所持角色的最大值（默认本人）
        int maxDataScope = 1;
        for (SysRole r : roles) {
            if (r.getDataScope() != null && r.getDataScope() > maxDataScope) {
                maxDataScope = r.getDataScope();
            }
        }

        // 查询角色-权限关联
        List<SysRolePermission> rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, enabledRoleIds));

        if (rolePerms.isEmpty()) {
            return new UserPermissions(roleCodes, List.of(), maxDataScope);
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

        return new UserPermissions(roleCodes, permCodes, maxDataScope);
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
            userRole.setTenantId(tenantId);
            userRoleMapper.insert(userRole);
        }
    }

    private LoginResponse buildLoginResponse(String token, String refreshToken,
                                              SysUser user, UserPermissions userPerms) {
        return LoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .storageUsed(user.getStorageUsed())
                .storageQuota(user.getStorageQuota())
                .roles(userPerms.roles)
                .permissions(userPerms.permissions)
                .build();
    }

    /**
     * 用户权限加载结果
     */
    private record UserPermissions(List<String> roles, List<String> permissions, int dataScope) {}
    private record UserSnapshot(SysUser user, UserPermissions permissions) {}
}
