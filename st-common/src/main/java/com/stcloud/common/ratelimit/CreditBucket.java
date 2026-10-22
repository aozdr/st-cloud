package com.stcloud.common.ratelimit;

/**
 * 跨节点信用桶的本地缓存。
 * <p>
 * 本地 tokens 只按 rate 从 credit（Redis 授予的额度）中补充，容量 = quantum；
 * credit 用尽时才向 Redis 申请。
 * 配置版本过期（stale）时通过 LimitSupplier 重新解析并重试，不进入降级；
 * 只有 Redis 异常才 fail-open 降级为本地按 rate 供给。
 */
final class CreditBucket {

    private final Object lock = new Object();
    private double tokens;
    private double credit;
    private long lastRefillNs;
    private long lastAccessMs = System.currentTimeMillis();
    private long degradedUntilMs;
    private final RateLimitStats stats;

    CreditBucket(RateLimitStats stats) {
        this.stats = stats;
    }

    void acquire(long bytes, long rateBps, long quantumBytes, CreditStore store,
                 String bucketKey, long ver, long ttlMs, boolean failOpen,
                 LimitSupplier refreshed, Runnable onStale) {
        long currentRate = Math.max(0L, rateBps);
        long currentVer = ver;
        long currentQuantum = Math.max(8192L, quantumBytes);
        int staleRetries = 0;
        while (true) {
            if (currentRate <= 0L) {
                return;
            }
            long waitMs = 0L;
            boolean needCredit = false;
            synchronized (lock) {
                long nowNs = System.nanoTime();
                if (lastRefillNs == 0L) {
                    lastRefillNs = nowNs;
                }
                double elapsedSec = Math.max(0D, (nowNs - lastRefillNs) / 1_000_000_000.0);
                lastRefillNs = nowNs;
                lastAccessMs = System.currentTimeMillis();
                boolean degraded = lastAccessMs < degradedUntilMs;
                if (degraded) {
                    tokens = Math.min(currentQuantum, tokens + elapsedSec * currentRate);
                } else {
                    double moved = Math.min(credit, elapsedSec * currentRate);
                    moved = Math.min(moved, Math.max(0D, currentQuantum - tokens));
                    if (moved > 0D) {
                        tokens += moved;
                        credit -= moved;
                    }
                }
                if (tokens >= bytes) {
                    tokens -= bytes;
                    return;
                }
                if (degraded) {
                    waitMs = (long) ((bytes - tokens) / (double) currentRate * 1000.0) + 1L;
                } else if (credit <= 0.0001D) {
                    needCredit = true;
                } else {
                    waitMs = (long) ((bytes - tokens) / (double) currentRate * 1000.0) + 1L;
                }
            }
            if (needCredit) {
                try {
                    CreditGrant grant = store.grant(bucketKey, currentQuantum, currentRate,
                            currentQuantum, currentQuantum, currentVer, ttlMs);
                    if (grant.stale()) {
                        if (stats != null) {
                            stats.stale();
                        }
                        if (onStale != null) {
                            onStale.run();
                        }
                        // 防御：连续 stale 且刷新结果不前进时退出，避免 EVAL 风暴
                        if (++staleRetries > 2) {
                            return;
                        }
                        CreditLimit fresh = refresh(refreshed);
                        if (fresh == null || fresh.rateBytes() <= 0L) {
                            return;
                        }
                        if (fresh.rateBytes() == currentRate && fresh.ver() == currentVer) {
                            return;
                        }
                        currentRate = fresh.rateBytes();
                        currentVer = fresh.ver();
                        currentQuantum = Math.max(8192L, fresh.quantumBytes());
                        continue;
                    }
                    if (grant.granted()) {
                        if (stats != null) {
                            stats.granted();
                        }
                        synchronized (lock) {
                            credit += currentQuantum;
                        }
                        continue;
                    }
                    if (stats != null) {
                        stats.denied();
                    }
                    if (!sleep(Math.min(Math.max(grant.waitMs(), 1L), 1000L))) {
                        return;
                    }
                } catch (RuntimeException e) {
                    // Redis 不可用：默认 fail-open，短时降级为本地按 rate 供给；
                    // 配置 failOpen=false 时向上抛出，由调用方中断传输。
                    if (!failOpen) {
                        throw e;
                    }
                    if (stats != null) {
                        stats.storeError();
                        stats.degraded();
                    }
                    synchronized (lock) {
                        degradedUntilMs = System.currentTimeMillis() + 5000L;
                    }
                }
                continue;
            }
            if (!sleep(Math.min(waitMs, 1000L))) {
                return;
            }
        }
    }

    private CreditLimit refresh(LimitSupplier refreshed) {
        if (refreshed == null) {
            return null;
        }
        try {
            return refreshed.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return false 表示线程被中断，调用方应立即退出。 */
    private boolean sleep(long waitMs) {
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    long lastAccessMs() {
        return lastAccessMs;
    }
}
