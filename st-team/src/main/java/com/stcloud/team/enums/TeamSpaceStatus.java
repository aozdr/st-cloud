package com.stcloud.team.enums;

import lombok.Getter;

/**
 * 团队空间状态（对应 team_space.status）。
 * 0-禁用 / 1-正常，与既有数据库语义保持一致。
 */
@Getter
public enum TeamSpaceStatus {
    DISABLED(0, "禁用"),
    NORMAL(1, "正常");

    private final int code;
    private final String desc;

    TeamSpaceStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TeamSpaceStatus fromCode(int code) {
        for (TeamSpaceStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TeamSpaceStatus code: " + code);
    }
}
