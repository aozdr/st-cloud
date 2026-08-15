package com.stcloud.share.enums;

import lombok.Getter;

/**
 * 分享状态（对应 file_share.status）。
 * 0-已取消 / 1-有效，与既有数据库语义保持一致。
 */
@Getter
public enum ShareStatus {
    CANCELLED(0, "已取消"),
    ACTIVE(1, "有效");

    private final int code;
    private final String desc;

    ShareStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ShareStatus fromCode(int code) {
        for (ShareStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ShareStatus code: " + code);
    }
}
