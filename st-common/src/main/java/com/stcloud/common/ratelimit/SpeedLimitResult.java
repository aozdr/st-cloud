package com.stcloud.common.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 限速解析结果 - 上传/下载速度上限(KB/s),0表示不限速
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpeedLimitResult {
    private int uploadSpeedLimit;
    private int downloadSpeedLimit;

    public static SpeedLimitResult unlimited() {
        return new SpeedLimitResult(0, 0);
    }
}