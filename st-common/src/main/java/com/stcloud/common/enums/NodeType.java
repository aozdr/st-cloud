package com.stcloud.common.enums;

import lombok.Getter;

@Getter
public enum NodeType {
    FILE(1, "文件"),
    FOLDER(0, "文件夹");

    private final int code;
    private final String desc;

    NodeType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static NodeType fromCode(int code) {
        for (NodeType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown NodeType code: " + code);
    }
}
