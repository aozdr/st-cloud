package com.stcloud.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CreditBucket 的版本自愈与计数语义（不依赖 Redis）。 */
@DisplayName("CreditBucket 版本自愈")
class CreditBucketTest {

    @Test
    @DisplayName("stale 时重新解析限速并重试，不进入降级")
    void staleRefreshesLimitAndRetries() {
        AtomicInteger grants = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        CreditStore store = new CreditStore() {
            @Override
            public CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                                     long initBytes, long ver, long ttlMs) {
                return grants.incrementAndGet() == 1
                        ? new CreditGrant(false, 0L, 0L, 9L, true)
                        : new CreditGrant(true, 0L, 1_000_000L, 9L, false);
            }

            @Override
            public CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                                             long capacityBytes, long initBytes, long ver, long ttlMs,
                                             int windowSize, String chunkKey, long windowTtlMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean releaseWindow(String windowKey, String chunkKey) {
                return false;
            }
        };
        LimitSupplier supplier = () -> {
            refreshes.incrementAndGet();
            return new CreditLimit(1_000_000L, 9L, 8192L);
        };

        CreditBucket bucket = new CreditBucket(null);
        bucket.acquire(8192L, 500_000L, 8192L, store, "rl:{t:1}:down", 8L, 60_000L,
                true, supplier, null);

        assertEquals(2, grants.get(), "stale 后应重试一次 grant");
        assertEquals(1, refreshes.get(), "stale 应触发一次限速重解析");
    }

    @Test
    @DisplayName("Redis 异常时 fail-open 不抛异常")
    void storeFailureFallsBack() {
        CreditStore store = new CreditStore() {
            @Override
            public CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                                     long initBytes, long ver, long ttlMs) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                                             long capacityBytes, long initBytes, long ver, long ttlMs,
                                             int windowSize, String chunkKey, long windowTtlMs) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean releaseWindow(String windowKey, String chunkKey) {
                throw new IllegalStateException("redis down");
            }
        };
        CreditBucket bucket = new CreditBucket(null);
        // 降级后按 1MB/s 本地补充，8192 字节应在毫秒级完成
        long start = System.nanoTime();
        bucket.acquire(8192L, 1_000_000L, 8192L, store, "rl:{t:2}:down", 1L, 60_000L,
                true, null, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 1000L, "fail-open 应立即降级而不是抛异常或长时间阻塞");
    }

    @Test
    @DisplayName("stale 且刷新值未前进：进入 5s 本地降级，不整块放行也不反复 EVAL")
    void staleWithoutProgressDegradesLocally() {
        AtomicInteger grants = new AtomicInteger();
        RateLimitStats stats = new RateLimitStats();
        CreditBucket bucket = new CreditBucket(stats);

        long start = System.nanoTime();
        bucket.acquire(8192L, 1_000_000L, 8192L, staleStore(grants), "rl:{t:3}:down", 8L, 60_000L,
                true, () -> new CreditLimit(1_000_000L, 8L, 8192L), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(1, grants.get(), "同值 stale 只应产生一次 EVAL，不能无限重试");
        assertEquals(1L, stats.snapshot().get("stale"));
        assertEquals(1L, stats.snapshot().get("degraded"), "版本僵持必须计入降级指标");
        assertTrue(elapsedMs < 1000L, "降级后应按当前 rate 平滑放行，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("stale 且刷新值持续前进：最多 3 次 EVAL 后转本地降级")
    void staleRetryExhaustionDegradesAfterBoundedEvals() {
        AtomicInteger grants = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        RateLimitStats stats = new RateLimitStats();
        CreditBucket bucket = new CreditBucket(stats);
        LimitSupplier supplier = () -> {
            int round = refreshes.incrementAndGet();
            return new CreditLimit(1_000_000L + round * 1_000L, 8L + round, 8192L);
        };

        bucket.acquire(8192L, 1_000_000L, 8192L, staleStore(grants), "rl:{t:4}:down", 8L, 60_000L,
                true, supplier, null);

        assertEquals(3, grants.get(), "EVAL 次数必须有上界");
        assertEquals(2, refreshes.get(), "耗尽后不再解析限速");
        assertEquals(1L, stats.snapshot().get("degraded"));
    }

    @Test
    @DisplayName("Redis 建议等待期间线程中断：立即退出并保留中断标记")
    void interruptDuringDeniedWaitExits() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        CreditBucket bucket = new CreditBucket(null);
        Thread thread = new Thread(() -> {
            bucket.acquire(8192L, 1_000_000L, 8192L, deniedStore(1_000L), "rl:{t:5}:down", 1L, 60_000L,
                    true, null, null);
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "credit-bucket-interrupt");
        thread.start();
        Thread.sleep(100L);
        thread.interrupt();
        thread.join(3_000L);

        assertFalse(thread.isAlive(), "中断后 acquire 应立即返回");
        assertTrue(interrupted.get(), "应恢复线程中断标记");
    }

    /** 每次都返回 stale 的假 store（rate/ver 回给调用方不需要被采纳）。 */
    private static CreditStore staleStore(AtomicInteger grants) {
        return new CreditStore() {
            @Override
            public CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                                     long initBytes, long ver, long ttlMs) {
                grants.incrementAndGet();
                return new CreditGrant(false, 0L, 0L, 9L, true);
            }

            @Override
            public CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                                             long capacityBytes, long initBytes, long ver, long ttlMs,
                                             int windowSize, String chunkKey, long windowTtlMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean releaseWindow(String windowKey, String chunkKey) {
                return false;
            }
        };
    }

    /** 固定拒绝并建议等待的假 store。 */
    private static CreditStore deniedStore(long waitMs) {
        return new CreditStore() {
            @Override
            public CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                                     long initBytes, long ver, long ttlMs) {
                return CreditGrant.denied(waitMs);
            }

            @Override
            public CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                                             long capacityBytes, long initBytes, long ver, long ttlMs,
                                             int windowSize, String chunkKey, long windowTtlMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean releaseWindow(String windowKey, String chunkKey) {
                return false;
            }
        };
    }
}
