package com.stcloud.sync.enums;

import lombok.Getter;

/**
 * 同步根状态（对应 sync_root.status）。
 * 0-启用 / 1-暂停，与既有数据库语义保持一致。
 */
@Getter
public enum SyncRootStatus {
    ACTIVE(0, "启用"),
    PAUSED(1, "暂停");

    private final int code;
    private final String desc;

    SyncRootStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SyncRootStatus fromCode(int code) {
        for (SyncRootStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown SyncRootStatus code: " + code);
    }
}
