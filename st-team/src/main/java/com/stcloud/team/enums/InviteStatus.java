package com.stcloud.team.enums;

import lombok.Getter;

/**
 * 团队邀请链接状态（对应 team_invite.status）。
 * 0-已撤销 / 1-有效，与既有数据库语义保持一致。
 */
@Getter
public enum InviteStatus {
    REVOKED(0, "已撤销"),
    ACTIVE(1, "有效");

    private final int code;
    private final String desc;

    InviteStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static InviteStatus fromCode(int code) {
        for (InviteStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown InviteStatus code: " + code);
    }
}
