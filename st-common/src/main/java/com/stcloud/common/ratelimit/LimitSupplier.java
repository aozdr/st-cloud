package com.stcloud.common.ratelimit;

/** 重新解析当前生效限速；stale 时由信用桶回调，实现在途流自愈。 */
@FunctionalInterface
public interface LimitSupplier {
    CreditLimit get();
}
