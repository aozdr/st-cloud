package com.stcloud.common.ratelimit;

/**
 * 一次限速解析的快照：字节速率、配置版本与对应 quantum。
 */
public record CreditLimit(long rateBytes, long ver, long quantumBytes) {

    /** 调用方只需要提供速率与版本，quantum 由限速器按配置计算。 */
    public CreditLimit(long rateBytes, long ver) {
        this(rateBytes, ver, 0L);
    }
}
