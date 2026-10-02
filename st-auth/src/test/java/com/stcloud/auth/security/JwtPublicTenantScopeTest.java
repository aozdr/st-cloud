package com.stcloud.auth.security;

import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.ServletException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JwtPublicTenantScopeTest {
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            mock(JwtUtils.class), mock(UserSecurityService.class), mock(StringRedisTemplate.class));

    @AfterEach
    void clear() { TenantContext.clear(); UserContext.clear(); SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/ping",
            "/api/share/captcha", "/api/share/access/example", "/api/file/42/editor/callback"})
    void publicBusinessEstablishesAndClearsExplicitDefaultTenant(String uri) throws Exception {
        filter.doFilter(new MockHttpServletRequest("POST", uri), new MockHttpServletResponse(),
                (request, response) -> assertEquals(1L, TenantContext.getTenantIdOrNull()));
        assertNull(TenantContext.getTenantIdOrNull());
    }

    @Test
    void existingVerifiedTenantIsPreservedAndNextProtectedRequestDoesNotInheritDefault() throws Exception {
        TenantContext.setTenantId(72L);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/share/access/example"), new MockHttpServletResponse(),
                (request, response) -> assertEquals(72L, TenantContext.getTenantIdOrNull()));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/file/list"), new MockHttpServletResponse(),
                (request, response) -> assertNull(TenantContext.getTenantIdOrNull()));
        assertNull(TenantContext.getTenantIdOrNull());
    }

    @Test
    void downstreamExceptionStillClearsPublicScope() {
        assertThrows(ServletException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/api/auth/login"), new MockHttpServletResponse(),
                (request, response) -> { assertEquals(1L, TenantContext.getTenantIdOrNull()); throw new ServletException("fixture"); }));
        assertNull(TenantContext.getTenantIdOrNull());
    }
}
