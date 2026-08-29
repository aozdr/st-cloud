package com.stcloud.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 用户上下文 - 基于ThreadLocal在请求生命周期内传递当前登录用户信息
 */
public class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT_USER = new ThreadLocal<>();

    public static void setCurrentUser(CurrentUser user) {
        CURRENT_USER.set(user);
    }

    public static CurrentUser getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static Long getUserId() {
        CurrentUser user = getCurrentUser();
        return user != null ? user.getUserId() : null;
    }

    public static Long getTenantId() {
        CurrentUser user = getCurrentUser();
        return user != null ? user.getTenantId() : null;
    }

    public static String getUsername() {
        CurrentUser user = getCurrentUser();
        return user != null ? user.getUsername() : null;
    }

    /**
     * 编程式权限校验：检查当前用户是否拥有指定权限码
     */
    public static boolean hasPermission(String code) {
        CurrentUser user = getCurrentUser();
        return user != null && user.permissions != null && user.permissions.contains(code);
    }

    /**
     * 编程式角色校验：检查当前用户是否拥有指定角色编码
     */
    public static boolean hasRole(String role) {
        CurrentUser user = getCurrentUser();
        return user != null && user.roles != null && user.roles.contains(role);
    }

    /**
     * 数据范围校验：当前用户是否可访问全部数据（dataScope >= 3）。
     * 替代散落的 hasRole("admin") 越权旁路，由角色 data_scope 驱动。
     */
    public static boolean canAccessAll() {
        CurrentUser user = getCurrentUser();
        return user != null && user.getDataScope() != null && user.getDataScope() >= 3;
    }

    /**
     * 当前为单租户部署、无租户切换：数据范围(dataScope)不再用于跨用户/跨租户数据访问，
     * 统一视为「本人」范围。个人文件一律属主可见/可操作，避免租户/全部 scope 泄漏他人文件。
     */
    public static boolean canAccessTenant() {
        return false;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUser {
        private Long userId;
        private Long tenantId;
        private String username;
        private String nickname;
        private String avatar;
        /** 角色编码列表 */
        private List<String> roles;
        /** 权限码集合 */
        private Set<String> permissions;
        /** 数据范围：1-本人 2-租户 3-全部 */
        private Integer dataScope;
    }
}
