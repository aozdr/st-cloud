package com.stcloud.common.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 下的信用桶验证：窗口原子性、释放幂等、版本回读、多实例并发不超发。
 * 通过 -Dtest.redis.port=6379 启用（与 UserSecurityRedisIntegrationTest 约定一致）。
 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "[0-9]+")
@DisplayName("Redis 信用桶集成测试")
class RedisCreditStoreTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static RedisCreditStore store;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("test.redis.port"));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        store = new RedisCreditStore(template);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private String key(String suffix) {
        return "rl:{it:" + UUID.randomUUID().toString().replace("-", "") + "}:" + suffix;
    }

    @Test
    @DisplayName("上传窗口：满窗拒绝不扣令牌，重复释放无效")
    void uploadWindowIsAtomicAndReleaseIsIdempotent() {
        String bucket = key("up");
        String window = bucket.replace(":up", ":win");
        long rate = 1_000_000L;
        long chunk = 1024L;
        try {
            CreditGrant first = store.acquireUpload(bucket, window, chunk, rate, chunk, chunk,
                    0L, 60_000L, 1, "u:1", 60_000L);
            assertTrue(first.granted());
            assertEquals(1L, template.opsForZSet().zCard(window));

            CreditGrant second = store.acquireUpload(bucket, window, chunk, rate, chunk, chunk,
                    0L, 60_000L, 1, "u:2", 60_000L);
            assertFalse(second.granted(), "窗口已满时应拒绝");
            assertEquals(1L, template.opsForZSet().zCard(window), "拒绝时不得写入窗口");

            assertTrue(store.releaseWindow(window, "u:1"));
            assertFalse(store.releaseWindow(window, "u:1"), "重复释放必须无效");
            assertEquals(0L, template.opsForZSet().zCard(window));

            Thread.sleep(10L);
            CreditGrant third = store.acquireUpload(bucket, window, chunk, rate, chunk, chunk,
                    0L, 60_000L, 1, "u:2", 60_000L);
            assertTrue(third.granted(), "释放后应允许下一片");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            template.delete(List.of(bucket, window));
        }
    }

    @Test
    @DisplayName("upload-window=0 表示不限在途数量")
    void zeroWindowMeansUnlimited() {
        String bucket = key("up");
        String window = bucket.replace(":up", ":win");
        long rate = 1_000_000L;
        long chunk = 1024L;
        try {
            CreditGrant first = store.acquireUpload(bucket, window, chunk, rate, chunk, chunk,
                    0L, 60_000L, 0, "u:1", 60_000L);
            assertTrue(first.granted());
            Thread.sleep(5L);
            CreditGrant second = store.acquireUpload(bucket, window, chunk, rate, chunk, chunk,
                    0L, 60_000L, 0, "u:2", 60_000L);
            assertTrue(second.granted(), "window=0 不应限制在途数量");
            assertEquals(0L, template.opsForZSet().zCard(window), "window=0 不应写窗口");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            template.delete(List.of(bucket, window));
        }
    }

    @Test
    @DisplayName("版本回读：旧节点不会覆盖新限速")
    void staleVersionReturnsCurrentConfig() {
        String bucket = key("down");
        try {
            CreditGrant current = store.grant(bucket, 10_000L, 1_000_000L, 100_000L, 10_000L,
                    5L, 60_000L);
            assertTrue(current.granted());

            CreditGrant stale = store.grant(bucket, 10_000L, 2_000_000L, 200_000L, 10_000L,
                    4L, 60_000L);
            assertTrue(stale.stale(), "旧版本应被识别为过期");
            assertEquals(1_000_000L, stale.rate(), "应回读共享桶中的新速率");
            assertEquals(5L, stale.ver());
        } finally {
            template.delete(bucket);
        }
    }

    @Test
    @DisplayName("两实例并发 1 秒：总发放不超过 rate + 初始 quantum")
    void concurrentGrantsDoNotExceedRate() throws Exception {
        String bucket = key("down");
        long rate = 100_000L;
        long quantum = 10_000L;
        long capacity = 100_000L;
        int threads = 4;
        long durationMs = 1000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong grantedBytes = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long end = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < end) {
                        CreditGrant grant = store.grant(bucket, quantum, rate, capacity,
                                quantum, 1L, 60_000L);
                        if (grant.granted()) {
                            grantedBytes.addAndGet(quantum);
                        } else {
                            Thread.sleep(Math.min(Math.max(grant.waitMs(), 1L), 100L));
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10L, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            template.delete(bucket);
        }
        long total = grantedBytes.get();
        assertTrue(total <= rate + quantum + rate / 5, "总发放 " + total + " 超过上限");
        assertTrue(total >= rate / 2, "总发放 " + total + " 偏低，脚本可能未生效");
    }
}
