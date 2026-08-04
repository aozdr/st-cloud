package com.stcloud.common.enums;

import lombok.Getter;

@Getter
public enum NodeStatus {
    NORMAL(0, "正常"),
    RECYCLED(1, "回收站"),
    DELETED(2, "已删除");

    private final int code;
    private final String desc;

    NodeStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static NodeStatus fromCode(int code) {
        for (NodeStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown NodeStatus code: " + code);
    }
}
