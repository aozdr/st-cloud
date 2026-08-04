package com.stcloud.core.enums;

import lombok.Getter;

@Getter
public enum UploadStatus {
    PENDING(0, "待上传"),
    UPLOADING(1, "上传中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "失败");

    private final int code;
    private final String desc;

    UploadStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UploadStatus fromCode(int code) {
        for (UploadStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown UploadStatus code: " + code);
    }
}
