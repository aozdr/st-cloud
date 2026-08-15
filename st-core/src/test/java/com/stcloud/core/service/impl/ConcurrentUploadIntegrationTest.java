package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发上传集成测试（TASK-006）。
 * 验证：多用户同时上传各自配额内互不影响；同文件（同 md5）同时上传去重为单一对象、引用计数正确。
 * 不依赖类级 @Transactional：并发线程以独立 autocommit 事务执行，真正并发竞争。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(ConcurrentUploadIntegrationTest.ConcurrentConfig.class)
class ConcurrentUploadIntegrationTest {

    @TestConfiguration
    static class ConcurrentConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        VersionService versionService() {
            return Mockito.mock(VersionService.class);
        }

        @Bean
        UploadManager uploadManager() {
            return new UploadManager();
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }
    }

    @Autowired
    private UploadManager uploadManager;

    @Autowired
    private FileObjectService fileObjectService;

    @Autowired
    private FileObjectMapper fileObjectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE id IN (501, 502)");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1 AND md5 LIKE 'md5-race%'");
        TenantContext.clear();
    }

    @Test
    void concurrentDistinctUsers_respectOwnQuota() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        // 两个用户各配额 5000
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (501, 1, 'cu-501', 'x', 1, 0, 5000, 0)");
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (502, 1, 'cu-502', 'x', 1, 0, 5000, 0)");

        int threads = 6; // 每用户 3 个并发请求
        long delta = 1000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final Long userId = (i % 2 == 0) ? 501L : 502L;
            pool.submit(() -> {
                TenantContext.setTenantId(1L);
                TenantContext.setTenantMode("SAAS");
                ready.countDown();
                try {
                    start.await();
                    uploadManager.consumeQuota(userId, null, delta);
                } catch (BusinessException e) {
                    failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
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

        // 每用户 3 次 × 1000 = 3000，均在各自 5000 配额内，全部成功、互不影响
        assertEquals(0, failed.get(), "各自配额内并发上传不应失败");
        assertEquals(3000L, jdbcTemplate.queryForObject("SELECT storage_used FROM sys_user WHERE id = 501", Long.class).longValue());
        assertEquals(3000L, jdbcTemplate.queryForObject("SELECT storage_used FROM sys_user WHERE id = 502", Long.class).longValue());
    }

    @Test
    void concurrentSameMd5Uploads_dedupToSingleObject() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Long> objectIds = new ConcurrentLinkedQueue<>();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                TenantContext.setTenantId(1L);
                TenantContext.setTenantMode("SAAS");
                ready.countDown();
                try {
                    start.await();
                    FileObject obj = fileObjectService.acquire(1L, "md5-race-001", 100L, () -> "t1/md5-race-001");
                    if (obj != null) {
                        objectIds.add(obj.getId());
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就绪");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发去重超时");
        pool.shutdownNow();
        TenantContext.clear();

        assertEquals(0, unexpected.get(), "并发去重不应出现意外异常");
        List<Long> ids = objectIds.stream().collect(Collectors.toList());
        assertEquals(threads, ids.size(), "所有线程都应获得对象");
        assertEquals(1L, ids.stream().distinct().count(), "同 md5 并发上传必须去重为单一对象");
        Long objId = ids.get(0);
        // 每个线程都 +1 引用：最终引用计数 = 线程数（插入方计 1，其余各 +1）
        assertEquals(threads, fileObjectMapper.getRefCount(objId), "对象引用计数应等于上传次数");
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        assertNotNull(fileObjectService.findByTenantAndMd5(1L, "md5-race-001"));
        TenantContext.clear();
    }
}
