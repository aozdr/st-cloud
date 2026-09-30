package com.stcloud.auth.service;

import com.stcloud.auth.entity.SysTenant;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.enums.TenantStatus;
import com.stcloud.auth.enums.UserStatus;
import com.stcloud.auth.mapper.SysTenantMapper;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.TenantContext;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 每次认证读取主库中的用户状态与会话版本；无效声明一律拒绝。 */
@Service
@RequiredArgsConstructor
public class UserSecurityService {
    private final SysUserMapper userMapper;
    private final SysTenantMapper tenantMapper;

    public boolean isCurrentAccess(Claims claims) {
        return claims != null && "access".equals(claims.get("type"))
                && isCurrent(claims.get("userId"), claims.get("tenantId"), claims.get("securityVersion"));
    }

    public boolean isCurrent(Object userIdClaim, Object tenantIdClaim, Object versionClaim) {
        Long userId = exactNonNegativeLong(userIdClaim);
        Long tenantId = exactNonNegativeLong(tenantIdClaim);
        Long version = exactNonNegativeLong(versionClaim);
        if (userId == null || userId <= 0 || tenantId == null || tenantId <= 0 || version == null) return false;
        // JWT 尚未建立请求上下文：保存原始值，避免正常认证误触发默认租户告警，或恢复成租户1。
        Long previousTenantId = TenantContext.getTenantIdOrNull();
        try {
            TenantContext.setTenantId(tenantId);
            // selectById 读取主库当前状态；令牌租户同时用于租户过滤和显式匹配。
            SysUser user = userMapper.selectSecurityState(tenantId, userId);
            if (user == null || !Objects.equals(user.getTenantId(), tenantId)
                    || !Integer.valueOf(UserStatus.NORMAL.getCode()).equals(user.getStatus())
                    || !Objects.equals(user.getSecurityVersion(), version)) return false;
            SysTenant tenant = tenantMapper.selectById(tenantId);
            return tenant != null && Integer.valueOf(TenantStatus.NORMAL.getCode()).equals(tenant.getStatus());
        } finally {
            TenantContext.setTenantId(previousTenantId);
        }
    }

    public Long exactNonNegativeLong(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return null;
        long number = ((Number) value).longValue();
        return number < 0 ? null : number;
    }

    public void increment(Long tenantId, Long userId) {
        if (userMapper.incrementSecurityVersion(tenantId, userId) != 1) {
            throw new IllegalStateException("用户安全版本更新失败");
        }
    }

    public void lockTenant(Long tenantId) {
        if (tenantId == null || !tenantId.equals(tenantMapper.lockSecurityWrites(tenantId))) {
            throw new IllegalStateException("租户安全写锁获取失败");
        }
    }

    public void incrementRoleMembers(Long tenantId, Long roleId) {
        userMapper.incrementRoleMembersSecurityVersion(tenantId, roleId);
    }
}
