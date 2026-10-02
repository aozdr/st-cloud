package com.stcloud.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.entity.SysTenant;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 SQL/签名验证默认入口与跨租户刷新，不通过日志降级掩盖漏设上下文。 */
class AuthTenantContextIntegrationTest extends AbstractAuthIntegrationTest {
    @Autowired private StringRedisTemplate redis;

    @Test
    void publicLoginSuccessAndFailureRestoreUnsetThreadWithoutFallbackWarnings() {
        insertUser("tenant-entry-login", "secret123", 1);
        TenantContext.clear();
        Logger logger = (Logger) LoggerFactory.getLogger(TenantContext.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            LoginRequest request = new LoginRequest();
            request.setUsername("tenant-entry-login");
            request.setPassword("secret123");
            assertNotNull(authService.login(request, "127.0.0.1").getToken());
            assertNull(TenantContext.getTenantIdOrNull());
            request.setPassword("wrong-password");
            assertThrows(BusinessException.class, () -> authService.login(request, "127.0.0.1"));
            assertNull(TenantContext.getTenantIdOrNull());
            assertTrue(events.list.isEmpty(), "默认登录入口不应触发缺租户兜底告警");
            TenantContext.getTenantId();
            assertFalse(events.list.isEmpty(), "其它真正缺少上下文的调用仍须有告警");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void publicRegisterRestoresUnsetThreadAfterSuccessAndDuplicateFailure() {
        TenantContext.clear();
        RegisterRequest request = new RegisterRequest();
        request.setUsername("scope-" + UUID.randomUUID().toString().substring(0, 12));
        request.setPassword("secret123");
        LoginResponse response = authService.register(request);
        assertEquals(1L, jwtUtils.parseToken(response.getToken()).get("tenantId", Long.class));
        assertNull(TenantContext.getTenantIdOrNull());
        assertThrows(BusinessException.class, () -> authService.register(request));
        assertNull(TenantContext.getTenantIdOrNull());
    }

    @Test
    void validatedRefreshUsesTokenTenantAcrossRequestsAndRestoresOriginalContext() {
        SysTenant tenant = new SysTenant();
        tenant.setId(9502L);
        tenant.setTenantName("refresh scope");
        tenant.setTenantCode("refresh-scope");
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        TenantContext.setTenantId(9502L);
        SysUser inserted = insertUser("tenant-entry-refresh", "secret123", 1);
        SysUser user = userMapper.selectById(inserted.getId());
        String token = jwtUtils.generateRefreshToken(user.getId(), 9502L, user.getUsername(), user.getSecurityVersion());
        when(redisValueOperations.get("stcloud:refresh:" + user.getId())).thenReturn(token);
        doReturn(1L).when(redis).execute(ArgumentMatchers.any(), ArgumentMatchers.anyList(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        TenantContext.clear();

        LoginResponse response = authService.refreshToken(token);
        assertEquals(9502L, jwtUtils.parseToken(response.getToken()).get("tenantId", Long.class));
        assertNull(TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());
        when(redisValueOperations.get("stcloud:refresh:" + user.getId())).thenReturn(null);
        TenantContext.setTenantId(99L);
        assertThrows(BusinessException.class, () -> authService.refreshToken(token));
        assertEquals(99L, TenantContext.getTenantIdOrNull());
    }

    @Test
    void wrongTokenPurposeDoesNotSwitchTenant() {
        String access = jwtUtils.generateToken(1L, 1L, "admin", java.util.List.of(), java.util.List.of(), 1, 0L);
        TenantContext.setTenantId(99L);
        assertThrows(BusinessException.class, () -> authService.refreshToken(access));
        assertEquals(99L, TenantContext.getTenantIdOrNull());
    }
}
