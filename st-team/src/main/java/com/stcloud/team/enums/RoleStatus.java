package com.stcloud.team.enums;

import lombok.Getter;

/**
 * 团队自定义角色状态（对应 team_role.status）。
 * 0-停用 / 1-启用，与既有数据库语义保持一致。
 */
@Getter
public enum RoleStatus {
    DISABLED(0, "停用"),
    ENABLED(1, "启用");

    private final int code;
    private final String desc;

    RoleStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static RoleStatus fromCode(int code) {
        for (RoleStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown RoleStatus code: " + code);
    }
}
