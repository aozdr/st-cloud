package com.stcloud.core.task;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.mapper.TenantScanMapper;
import com.stcloud.core.service.RecycleBinService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证调度入口实际接入逐租户执行，受控 Redis/清理服务不删除真实数据。 */
class RecycleBinPurgeTenantTest {
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantScanFailureDoesNotBlockNextTenantAndReleasesLock() {
        TenantScanMapper mapper = mock(TenantScanMapper.class);
        when(mapper.selectEnabledTenantIds()).thenReturn(List.of(1L, 2L));
        RecycleBinService service = mock(RecycleBinService.class);
        when(service.findExpiredRecycleRoots()).thenAnswer(invocation -> {
            if (TenantContext.getTenantIdOrNull() == 1L) {
                throw new IllegalStateException("first tenant scan failed");
            }
            assertEquals(2L, TenantContext.getTenantIdOrNull());
            return List.of(201L);
        });
        doAnswer(invocation -> {
            assertEquals(2L, TenantContext.getTenantIdOrNull());
            return null;
        }).when(service).purgeNode(201L);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(eq("stcloud:lock:recycle-purge"), anyString(), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);
        new RecycleBinPurgeTask(service, redis, new TenantTaskRunner(mapper)).purgeExpiredRecycleBin();
        verify(service, times(2)).findExpiredRecycleRoots();
        verify(service).purgeNode(201L);
        verify(redis).execute(any(RedisScript.class), eq(List.of("stcloud:lock:recycle-purge")), anyString());
        assertNull(TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());
    }
}
