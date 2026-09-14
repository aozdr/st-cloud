package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0 规范对象回收竞争集成测试：真实 H2 + 两线程模拟候选复用与 GC 并发，
 * 证明活动上传存在时不会删除规范对象；不能用 JVM 锁替代数据库状态复核。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(ObjectCleanupRaceIntegrationTest.RaceConfig.class)
class ObjectCleanupRaceIntegrationTest {

    private static final long TENANT_ID = 991L;

    @TestConfiguration
    static class RaceConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        UploadStorageManager uploadStorageManager() {
            return new UploadStorageManager();
        }

        @Bean
        OrphanObjectCleanupService orphanObjectCleanupService() {
            return new OrphanObjectCleanupService();
        }
    }

    @Autowired
    private OrphanObjectCleanupService cleanupService;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private StorageService storageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM file_orphan_candidate WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = ?", TENANT_ID);
    }

    @Test
    void concurrentCandidateReuseAndGc_neverDeletesReusedCanonicalObject() throws Exception {
        useTenant();
        String md5 = "race-" + System.nanoTime();
        String storagePath = TENANT_ID + "/" + md5;
        ReflectionTestUtils.setField(cleanupService, "graceMs", 0L);

        // 第一个请求已登记并正在写入规范对象；第二个请求将并发复用同一路径。
        cleanupService.beginUpload(TENANT_ID, md5, storagePath);

        CountDownLatch reused = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> uploader = executor.submit(() -> {
                try {
                    useTenant();
                    cleanupService.beginUpload(TENANT_ID, md5, storagePath);
                    reused.countDown();
                    allowCommit.await();

                    FileObject object = new FileObject();
                    object.setTenantId(TENANT_ID);
                    object.setMd5(md5);
                    object.setSize(12L);
                    object.setStoragePath(storagePath);
                    object.setRefCount(1);
                    object.setStatus(0);
                    fileObjectMapper.insert(object);
                    cleanupService.markCommitted(TENANT_ID, storagePath);
                    return null;
                } finally {
                    reused.countDown();
                }
            });

            assertTrue(reused.await(10, TimeUnit.SECONDS),
                    "复用线程应在超时前完成 beginUpload；若失败由测试直接暴露而不是无限等待");
            // 旧请求此时失败，只能释放一个活动计数；新请求仍在写入，GC 不得领取候选。
            cleanupService.markFailed(TENANT_ID, storagePath);
            Future<?> gc = executor.submit(() -> {
                useTenant();
                cleanupService.scheduledCleanup();
                return null;
            });
            gc.get();
            allowCommit.countDown();
            uploader.get();

            verify(storageService, never()).deleteObject(anyString());
            assertEquals(0L, cleanupServiceCandidateCount(storagePath), "成功复用后候选应被提交路径移除");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            TenantContext.clear();
        }
    }

    private void useTenant() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setTenantMode("SAAS");
    }

    private long cleanupServiceCandidateCount(String storagePath) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_orphan_candidate WHERE tenant_id = ? AND storage_path = ?",
                Long.class, TENANT_ID, storagePath);
    }
}
