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

    public static boolean isAdmin() {
        CurrentUser user = getCurrentUser();
        return user != null && user.isAdmin();
    }

    /**
     * 编程式权限校验：is_admin 用户直接通过，否则检查权限码集合
     */
    public static boolean hasPermission(String code) {
        CurrentUser user = getCurrentUser();
        return user != null && (user.admin ||
                (user.permissions != null && user.permissions.contains(code)));
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
        private boolean admin;
        /** 角色编码列表 */
        private List<String> roles;
        /** 权限码集合 */
        private Set<String> permissions;
    }
}
