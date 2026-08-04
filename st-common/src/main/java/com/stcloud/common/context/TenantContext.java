package com.stcloud.common.context;

/**
 * 租户上下文 - 基于ThreadLocal在请求生命周期内传递租户ID
 */
public class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_MODE = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long getTenantId() {
        Long tenantId = TENANT_ID.get();
        if (tenantId == null) {
            // 私有云模式默认租户
            return 1L;
        }
        return tenantId;
    }

    public static void setTenantMode(String mode) {
        TENANT_MODE.set(mode);
    }

    public static String getTenantMode() {
        String mode = TENANT_MODE.get();
        return mode != null ? mode : "SAAS";
    }

    public static boolean isPrivateMode() {
        return "PRIVATE".equals(getTenantMode());
    }

    public static void clear() {
        TENANT_ID.remove();
        TENANT_MODE.remove();
    }
}
