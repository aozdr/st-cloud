package com.stcloud.core.enums;

import lombok.Getter;

/**
 * 文件上传状态机（TASK-002）。
 * 状态流转：INIT(0) -> UPLOADING(1) -> MERGING(4) -> COMPLETED(2=STORED)，异常 -> FAILED(3)，中止 -> DELETED(5)。
 * 合并失败可重试：FAILED(3) -> MERGING(4)（见 FileNodeMapper.claimMerging）。
 * 兼容历史数据：0-待上传 / 1-上传中 / 2-已完成 / 3-失败 语义不变，新增 4-合并中 / 5-已删除。
 */
@Getter
public enum UploadStatus {
    INIT(0, "已初始化"),
    UPLOADING(1, "上传中"),
    COMPLETED(2, "已完成(STORED)"),
    FAILED(3, "失败"),
    MERGING(4, "合并中"),
    DELETED(5, "已删除");

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

    /** 是否终态：已完成 / 已删除 不可再流转（删除需先清引用）；失败(3) 非终态，允许重试合并流转到 MERGING(4) */
    public boolean isTerminal() {
        return this == COMPLETED || this == DELETED;
    }
}
