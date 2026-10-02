package com.stcloud.core.task;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.mapper.TenantScanMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 调度线程跨租户、异常及下一轮复用的边界，不调用真实清理或外部服务。 */
class TenantTaskRunnerTest {
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void isolatesTenantFailureAndRestoresExactOriginalContext(Long originalTenant) {
        TenantScanMapper mapper = mock(TenantScanMapper.class);
        when(mapper.selectEnabledTenantIds()).thenReturn(List.of(1L, 2L));
        TenantTaskRunner runner = new TenantTaskRunner(mapper);
        TenantContext.setTenantId(originalTenant);
        List<Long> visited = new ArrayList<>();
        runner.runForEachTenant("test", () -> {
            Long tenantId = TenantContext.getTenantIdOrNull();
            assertEquals("SAAS", TenantContext.getTenantModeOrNull());
            visited.add(tenantId);
            if (tenantId == 1L) {
                TenantContext.setTenantId(777L);
                TenantContext.setTenantMode("PRIVATE");
                throw new IllegalStateException("租户1受控失败");
            }
            assertEquals(2L, tenantId);
        });
        assertEquals(List.of(1L, 2L), visited);
        assertEquals(originalTenant, TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());

        when(mapper.selectEnabledTenantIds()).thenReturn(List.of(2L));
        runner.runForEachTenant("next-run", () -> {
            assertEquals(2L, TenantContext.getTenantIdOrNull());
            assertEquals("SAAS", TenantContext.getTenantModeOrNull());
        });
        assertEquals(originalTenant, TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());
    }

    @Test
    void registryFailurePreservesExistingContextAndDoesNotRunAction() {
        TenantScanMapper mapper = mock(TenantScanMapper.class);
        when(mapper.selectEnabledTenantIds()).thenThrow(new IllegalStateException("registry unavailable"));
        TenantTaskRunner runner = new TenantTaskRunner(mapper);
        TenantContext.setTenantId(99L);
        TenantContext.setTenantMode("SAAS");
        assertThrows(IllegalStateException.class, () -> runner.runForEachTenant("test", () -> fail("不可执行")));
        assertEquals(99L, TenantContext.getTenantIdOrNull());
        assertEquals("SAAS", TenantContext.getTenantModeOrNull());
    }

    @Test
    void configuredPrivateModeRunsOnceAndRestoresUnsetSchedulerThread() {
        TenantScanMapper mapper = mock(TenantScanMapper.class);
        TenantTaskRunner runner = new TenantTaskRunner(mapper);
        ReflectionTestUtils.setField(runner, "configuredMode", "PRIVATE");
        List<Long> visited = new ArrayList<>();
        runner.runForEachTenant("private", () -> {
            visited.add(TenantContext.getTenantIdOrNull());
            assertEquals("PRIVATE", TenantContext.getTenantModeOrNull());
        });
        assertEquals(List.of(1L), visited);
        verifyNoInteractions(mapper);
        assertNull(TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());
    }

    @Test
    void privateCallbackFailureRestoresOriginalPrivateTenant() {
        TenantScanMapper mapper = mock(TenantScanMapper.class);
        TenantTaskRunner runner = new TenantTaskRunner(mapper);
        TenantContext.setTenantId(88L);
        TenantContext.setTenantMode("PRIVATE");
        assertThrows(IllegalStateException.class, () -> runner.runForEachTenant("private", () -> {
            assertEquals(88L, TenantContext.getTenantIdOrNull());
            TenantContext.clear();
            throw new IllegalStateException("private failure");
        }));
        verifyNoInteractions(mapper);
        assertEquals(88L, TenantContext.getTenantIdOrNull());
        assertEquals("PRIVATE", TenantContext.getTenantModeOrNull());
    }
}
