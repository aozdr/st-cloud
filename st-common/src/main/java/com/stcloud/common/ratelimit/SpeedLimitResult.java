package com.stcloud.common.ratelimit;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 限速解析结果 - 上传/下载速度上限(KB/s)，0 表示不限速。
 * version 用于共享桶校验配置新旧（不改变既有 API 语义）。
 */
@Data
@NoArgsConstructor
public class SpeedLimitResult {

    private int uploadSpeedLimit;
    private int downloadSpeedLimit;
    private long version;

    public SpeedLimitResult(int uploadSpeedLimit, int downloadSpeedLimit) {
        this(uploadSpeedLimit, downloadSpeedLimit, 0L);
    }

    public SpeedLimitResult(int uploadSpeedLimit, int downloadSpeedLimit, long version) {
        this.uploadSpeedLimit = uploadSpeedLimit;
        this.downloadSpeedLimit = downloadSpeedLimit;
        this.version = version;
    }

    public static SpeedLimitResult unlimited() {
        return new SpeedLimitResult(0, 0, 0L);
    }
}
