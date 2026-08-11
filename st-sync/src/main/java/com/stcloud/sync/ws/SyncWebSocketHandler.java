package com.stcloud.sync.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket 连接处理器 + 会话注册表
 * <p>
 * 维护 userId -> Set<WebSocketSession> 映射，支持同一用户多设备同时在线。
 * 心跳：服务端每 30s 发送 ping（由 Spring WebSocket 容器自动处理）。
 */
@Slf4j
@Component
public class SyncWebSocketHandler extends TextWebSocketHandler {

    /** userId -> 该用户所有在线设备的 WebSocket 会话集合 */
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        if (userId == null) {
            log.warn("WebSocket 连接缺少 userId，关闭会话");
            closeQuietly(session);
            return;
        }
        userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("WebSocket 连接建立：userId={}, sessionId={}, 在线设备={}",
                userId, session.getId(), userSessions.get(userId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = getUserId(session);
        if (userId != null) {
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
            log.info("WebSocket 连接关闭：userId={}, sessionId={}, status={}",
                    userId, session.getId(), status);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端心跳 pong 响应或其他消息在此处理；当前仅记录
        String payload = message.getPayload();
        if ("ping".equalsIgnoreCase(payload)) {
            try {
                session.sendMessage(new TextMessage("pong"));
            } catch (Exception e) {
                log.debug("发送 pong 失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输错误：sessionId={}, error={}", session.getId(), exception.getMessage());
    }

    /**
     * 向指定用户的所有在线设备推送消息
     *
     * @return 实际推送成功的会话数
     */
    public int sendToUser(Long userId, String message) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int sent = 0;
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                    sent++;
                } catch (Exception e) {
                    log.warn("推送消息失败：userId={}, sessionId={}, error={}",
                            userId, session.getId(), e.getMessage());
                }
            }
        }
        return sent;
    }

    /**
     * 获取指定用户当前在线的 WebSocket 会话数
     */
    public int getSessionCount(Long userId) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        return sessions != null ? sessions.size() : 0;
    }

    private Long getUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) { }
    }
}