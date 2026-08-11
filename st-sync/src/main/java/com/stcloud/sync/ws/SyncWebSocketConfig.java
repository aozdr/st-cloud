package com.stcloud.sync.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * <p>
 * 注册同步推送端点 /api/sync/ws，握手时通过 SyncAuthHandshakeInterceptor 鉴权。
 * 允许所有来源（CORS 由 SecurityConfig 统一管理），握手前拦截器校验 JWT。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class SyncWebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler syncWebSocketHandler;
    private final SyncAuthHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(syncWebSocketHandler, "/api/sync/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}