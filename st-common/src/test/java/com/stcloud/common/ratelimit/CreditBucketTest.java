package com.stcloud.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
