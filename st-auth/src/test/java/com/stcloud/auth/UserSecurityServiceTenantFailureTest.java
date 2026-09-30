package com.stcloud.auth;

import com.stcloud.auth.mapper.SysTenantMapper;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 异常路径也必须还原原始租户，包括原先未设置的状态。 */
class UserSecurityServiceTenantFailureTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void mapperFailureRestoresOriginalContext(Long originalTenant) {
        TenantContext.setTenantId(originalTenant);
        TenantContext.setTenantMode("PRIVATE");
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectSecurityState(1L, 7L)).thenAnswer(invocation -> {
            assertEquals(1L, TenantContext.getTenantIdOrNull());
            throw new IllegalStateException("database unavailable");
        });
        UserSecurityService service = new UserSecurityService(mapper, mock(SysTenantMapper.class));
        assertThrows(IllegalStateException.class, () -> service.isCurrent(7L, 1L, 0L));
        assertEquals(originalTenant, TenantContext.getTenantIdOrNull());
        assertEquals("PRIVATE", TenantContext.getTenantMode());
    }
}
