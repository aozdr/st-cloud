package com.stcloud.core.enums;

import lombok.Getter;

/**
 * 文件对象状态（对应 file_object.status）。
 * 0-正常 / 1-已删除（物理对象已清理，禁止再复用），与既有数据库语义保持一致。
 */
@Getter
public enum FileObjectStatus {
    NORMAL(0, "正常"),
    DELETED(1, "已删除");

    private final int code;
    private final String desc;

    FileObjectStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static FileObjectStatus fromCode(int code) {
        for (FileObjectStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown FileObjectStatus code: " + code);
    }
}
