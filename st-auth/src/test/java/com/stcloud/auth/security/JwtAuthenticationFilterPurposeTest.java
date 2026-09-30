package com.stcloud.auth.security;

import com.stcloud.auth.mapper.SysTenantMapper;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 令牌用途与流式路径约束，不依赖网络或共享 Redis。 */
class JwtAuthenticationFilterPurposeTest {
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserSecurityService security = new UserSecurityService(
            mock(SysUserMapper.class), mock(SysTenantMapper.class));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtils, security, redis);

    @AfterEach
    void clear() {
        TenantContext.clear();
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Claims claims(String type, Long nodeId) {
        return Jwts.claims().add(Map.of("type", type, "userId", 11L, "tenantId", 1L,
                        "roles", List.of("user"), "permissions", List.of("file:preview")))
                .subject("alice")
                .id("isolated-stream-id")
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .add("nodeId", nodeId == null ? 101L : nodeId)
                .build();
    }

    private boolean authenticated(String token, String uri, Claims claims) throws Exception {
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.parseToken(token)).thenReturn(claims);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer " + token);
        AtomicBoolean seen = new AtomicBoolean();
        TenantContext.setTenantId(99L);
        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> seen.set(UserContext.getUserId() != null
                        && SecurityContextHolder.getContext().getAuthentication() != null));
        assertNull(UserContext.getUserId(), "请求结束必须清理用户上下文");
        assertEquals(1L, TenantContext.getTenantId(), "请求结束必须清理之前的租户上下文");
        return seen.get();
    }

    @Test
    void refreshAndUnknownTypesNeverAuthorizeOrdinaryHttp() throws Exception {
        assertFalse(authenticated("refresh-token", "/api/file/list", claims("refresh", 101L)));
        assertFalse(authenticated("unknown-token", "/api/file/list", claims("unknown", 101L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadTokenOnlyAuthorizesBoundStreamPathAndFirstUse() throws Exception {
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(true);
        Claims download = claims("download", 101L);
        assertFalse(authenticated("download-other-path", "/api/file/list", download));
        assertFalse(authenticated("download-other-node", "/api/file/102/stream", download));
        assertTrue(authenticated("download-correct", "/api/file/101/stream", download));
    }

    @Test
    void editorTokenOnlyAuthorizesBoundStreamPath() throws Exception {
        Claims editor = claims("editor", 101L);
        assertFalse(authenticated("editor-other-path", "/api/file/list", editor));
        assertFalse(authenticated("editor-other-node", "/api/file/102/stream", editor));
        assertTrue(authenticated("editor-correct", "/api/file/101/stream", editor));
    }

    @Test
    void downstreamExceptionClearsThreadContextsBeforeNextRequest() throws Exception {
        String token = "editor-exception";
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.parseToken(token)).thenReturn(claims("editor", 101L));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/file/101/stream");
        request.addHeader("Authorization", "Bearer " + token);
        assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request,
                new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                    assertTrue(UserContext.getUserId() != null);
                    throw new jakarta.servlet.ServletException("injected downstream failure");
                }));
        assertNull(UserContext.getUserId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(1L, TenantContext.getTenantId());

        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/file/list");
        AtomicBoolean inherited = new AtomicBoolean();
        filter.doFilter(anonymous, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> inherited.set(UserContext.getUserId() != null));
        assertFalse(inherited.get());
    }
}
