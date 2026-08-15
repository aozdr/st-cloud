package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件对象去重/引用计数集成测试（TASK-001）。
 * 验证：同租户 md5 去重、跨租户隔离、引用增减、归零物理删除、失效后不可复用。
 */
@Import(FileObjectIntegrationTest.FileObjectTestConfig.class)
class FileObjectIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FileObjectTestConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
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
    private FileObjectMapper fileObjectMapper;

    @Autowired
    private FileObjectService fileObjectService;

    @Test
    void acquire_dedupSameTenantAndIsolateTenants() {
        // 上下文租户 1
        TenantContext.setTenantId(1L);
        FileObject o1 = fileObjectService.acquire(1L, "md5-aaa", 100L, () -> "t1/md5-aaa");
        // 同租户同 md5：复用同一对象，不重复创建
        FileObject o2 = fileObjectService.acquire(1L, "md5-aaa", 100L, () -> "should-not-be-used");
        assertEquals(o1.getId(), o2.getId());
        assertEquals("t1/md5-aaa", o2.getStoragePath());
        // 引用计数 +1（1 -> 2）
        assertEquals(2, fileObjectMapper.getRefCount(o1.getId()));

        // 切到租户 2：同 md5 各自独立对象（租户隔离由拦截器按 TenantContext 作用域保证）
        TenantContext.setTenantId(2L);
        FileObject o3 = fileObjectService.acquire(2L, "md5-aaa", 100L, () -> "t2/md5-aaa");
        assertNotNull(o3);
        assertNotEquals(o1.getId(), o3.getId());
        assertEquals(1, fileObjectMapper.getRefCount(o3.getId()));
        TenantContext.setTenantId(1L);
    }

    @Test
    void acquire_onlyUploadsWhenObjectMissing() {
        AtomicInteger uploads = new AtomicInteger();
        FileObject obj = fileObjectService.acquire(1L, "md5-bbb", 200L, () -> {
            uploads.incrementAndGet();
            return "t1/md5-bbb";
        });
        assertNotNull(obj.getId());
        assertEquals("t1/md5-bbb", obj.getStoragePath());
        assertEquals(1, obj.getRefCount());

        // 第二次同 md5：不再调用上传回调
        FileObject again = fileObjectService.acquire(1L, "md5-bbb", 200L, () -> {
            uploads.incrementAndGet();
            return "t1/md5-bbb-dup";
        });
        assertEquals(1, uploads.get());
        assertEquals(obj.getId(), again.getId());
        assertEquals(2, fileObjectMapper.getRefCount(obj.getId()));
    }

    @Test
    void releaseToZeroThenDeletePhysicalMakesObjectUnavailable() {
        FileObject obj = fileObjectService.acquire(1L, "md5-ccc", 300L, () -> "t1/md5-ccc");
        int remaining = fileObjectService.release(obj.getId());
        assertEquals(0, remaining);
        // 永久删除：引用归零后物理删除并标记失效
        fileObjectService.deletePhysical(obj.getId());
        assertNull(fileObjectService.findByTenantAndMd5(1L, "md5-ccc"));
    }

    @Test
    void releaseWithoutDeleteKeepsObjectReusable() {
        // 版本恢复场景：只减引用、不物理删除，对象仍可复用
        FileObject obj = fileObjectService.acquire(1L, "md5-ddd", 400L, () -> "t1/md5-ddd");
        fileObjectService.release(obj.getId());
        FileObject again = fileObjectService.acquire(1L, "md5-ddd", 400L, () -> "t1/md5-ddd");
        assertEquals(obj.getId(), again.getId());
        assertEquals(1, fileObjectMapper.getRefCount(obj.getId()));
    }

    @Test
    void insertIgnoreDuplicateKeepsSingleRow() {
        FileObject a = new FileObject();
        a.setTenantId(1L);
        a.setMd5("md5-eee");
        a.setSize(1L);
        a.setStoragePath("t1/e");
        a.setRefCount(1);
        a.setStatus(0);
        int r1 = fileObjectMapper.insertIgnore(a);
        int r2 = fileObjectMapper.insertIgnore(a);
        assertEquals(1, r1);
        assertEquals(0, r2);
        Long count = fileObjectMapper.selectCount(
                new LambdaQueryWrapper<FileObject>()
                        .eq(FileObject::getTenantId, 1L)
                        .eq(FileObject::getMd5, "md5-eee"));
        assertEquals(1L, count);
    }
}