package com.stcloud.sync.ws;

import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器
 * <p>
 * 从查询参数 token 中提取 JWT，验证有效后将 userId / tenantId 存入握手属性，
 * 供 SyncWebSocketHandler 在连接建立后读取。无效 token 拒绝握手。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtils jwtUtils;
    private final UserSecurityService userSecurityService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String token = httpRequest.getParameter("token");

            // 兼容两种传参：优先 Authorization: Bearer（桌面端去掉 JWT 入 URL），回退 query token
            if (token == null || token.isBlank()) {
                String auth = httpRequest.getHeader("Authorization");
                if (auth != null && auth.startsWith("Bearer ")) {
                    token = auth.substring("Bearer ".length()).trim();
                }
            }

            if (token == null || token.isBlank()) {
                log.warn("WebSocket 握手失败：缺少 token 参数");
                return false;
            }

            if (!jwtUtils.validateToken(token)) {
                log.warn("WebSocket 握手失败：token 无效或已过期");
                return false;
            }

            // 鉴权通过，将用户信息存入握手属性
            try {
                Claims claims = jwtUtils.parseToken(token);
                if (!userSecurityService.isCurrentAccess(claims)) return false;
                Long userId = userSecurityService.exactNonNegativeLong(claims.get("userId"));
                Long tenantId = userSecurityService.exactNonNegativeLong(claims.get("tenantId"));
                attributes.put("userId", userId);
                attributes.put("tenantId", tenantId);
                attributes.put("securityVersion", userSecurityService.exactNonNegativeLong(claims.get("securityVersion")));
                attributes.put("expiresAt", claims.getExpiration().getTime());
                log.debug("WebSocket 握手成功：userId={}", userId);
                return true;
            } catch (Exception e) {
                log.warn("WebSocket 握手失败：认证状态不可用");
                return false;
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需后处理
    }
}
