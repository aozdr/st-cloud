package com.stcloud.auth.enums;

import lombok.Getter;

/**
 * 用户状态（对应 sys_user.status）。
 * 0-禁用 / 1-正常，与既有数据库语义保持一致。
 */
@Getter
public enum UserStatus {
    DISABLED(0, "禁用"),
    NORMAL(1, "正常");

    private final int code;
    private final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UserStatus fromCode(int code) {
        for (UserStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown UserStatus code: " + code);
    }
}
