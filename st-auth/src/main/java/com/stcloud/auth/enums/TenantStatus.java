package com.stcloud.auth.enums;

import lombok.Getter;

/**
 * 租户状态（对应 sys_tenant.status）。
 * 0-停用 / 1-正常，与既有数据库语义保持一致。
 */
@Getter
public enum TenantStatus {
    DISABLED(0, "停用"),
    NORMAL(1, "正常");

    private final int code;
    private final String desc;

    TenantStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TenantStatus fromCode(int code) {
        for (TenantStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TenantStatus code: " + code);
    }
}
