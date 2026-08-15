package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量并发原子化集成测试（TASK-003）。
 * 验证：10 线程竞争同一配额（quota=5000，每请求 1000），仅 5 个成功、5 个抛 STORAGE_QUOTA_EXCEEDED，
 * 最终 used 精确等于 5000（不超卖、不为负）。
 * 不依赖类级 @Transactional：各线程以独立 autocommit 事务执行原子条件 UPDATE，真正并发竞争。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(QuotaConcurrencyIntegrationTest.QuotaConfig.class)
class QuotaConcurrencyIntegrationTest {

    @TestConfiguration
    static class QuotaConfig {
        @Bean
        VersionService versionService() {
            return org.mockito.Mockito.mock(VersionService.class);
        }

        @Bean
        UploadManager uploadManager() {
            return new UploadManager();
        }
    }

    @Autowired
    private UploadManager uploadManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 777");
        TenantContext.clear();
    }

    @Test
    void concurrentUploadsDoNotOversellQuota() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        // 用户配额 5000，已用 0
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (777, 1, 'quota-test', 'x', 1, 0, 5000, 0)");

        int threads = 10;
        long delta = 1000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                // 租户拦截器按 ThreadLocal 取租户，每个线程需设置
                TenantContext.setTenantId(1L);
                TenantContext.setTenantMode("SAAS");
                ready.countDown();
                try {
                    start.await();
                    uploadManager.consumeQuota(777L, null, delta);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就绪");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发扣减超时");

        pool.shutdownNow();
        TenantContext.clear();

        Long used = jdbcTemplate.queryForObject("SELECT storage_used FROM sys_user WHERE id = 777", Long.class);
        assertEquals(5000L, used.longValue(), "并发上传超配额不得超卖，used 必须精确等于配额");
        assertEquals(5, success.get(), "quota=5000、每请求 1000 时应恰有 5 个成功");
        assertEquals(5, rejected.get(), "其余 5 个应抛 STORAGE_QUOTA_EXCEEDED");
        assertEquals(0, unexpected.get(), "不应出现锁超时等意外异常");
    }
}