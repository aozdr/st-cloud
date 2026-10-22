package com.stcloud.common.ratelimit;

/**
 * 一次信用申请的结果。
 * <p>
 * granted=true 表示已从共享桶原子扣减；否则 waitMs 为建议等待时长。
 * stale=true 表示调用方携带的配置版本落后，需刷新限速缓存后重试。
 */
public record CreditGrant(boolean granted, long waitMs, long rate, long ver, boolean stale) {

    public static CreditGrant denied(long waitMs) {
        return new CreditGrant(false, Math.max(1L, waitMs), 0L, 0L, false);
    }
}
