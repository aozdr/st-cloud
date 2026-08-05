package com.stcloud.common.context;

import lombok.extern.slf4j.Slf4j;

/**
 * 租户上下文 - 基于 ThreadLocal 在请求生命周期内传递租户 ID
 */
@Slf4j
public class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_MODE = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long getTenantId() {
        Long tenantId = TENANT_ID.get();
        if (tenantId != null) {
            return tenantId;
        }
        if (isPrivateMode()) {
            return 1L;
        }
        // SAAS 模式下未设置租户上下文：登录/公开接口等无 token 场景依赖兜底，
        // 保留默认租户但记录告警，便于排查遗漏设置 TenantContext 的调用链
        log.warn("SAAS 模式下租户上下文未设置，兜底为默认租户 1；若非登录/公开接口场景，请检查调用链");
        return 1L;
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