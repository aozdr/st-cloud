package com.stcloud.core.enums;

import lombok.Getter;

/**
 * 分片上传状态（对应 file_chunk.status）。
 * 0-待上传 / 1-已上传 / 2-已合并，与既有数据库语义保持一致。
 */
@Getter
public enum FileChunkStatus {
    PENDING(0, "待上传"),
    UPLOADED(1, "已上传"),
    MERGED(2, "已合并");

    private final int code;
    private final String desc;

    FileChunkStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static FileChunkStatus fromCode(int code) {
        for (FileChunkStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown FileChunkStatus code: " + code);
    }
}
