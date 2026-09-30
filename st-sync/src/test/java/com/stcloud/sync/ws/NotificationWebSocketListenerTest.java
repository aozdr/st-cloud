package com.stcloud.sync.ws;

import com.stcloud.common.event.NotificationUnreadChangedEvent;
import com.stcloud.common.event.NotificationUnreadCountReader;
import com.stcloud.auth.service.UserSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class NotificationWebSocketListenerTest {

    @Test
    void notificationOnlyReachesAuthenticatedSessionInSameTenant() throws Exception {
        UserSecurityService securityService = mock(UserSecurityService.class);
        when(securityService.isCurrent(any(), any(), any())).thenReturn(true);
        SyncWebSocketHandler handler = new SyncWebSocketHandler(securityService);
        WebSocketSession recipient = session("recipient", 8L, 1L);
        WebSocketSession otherTenant = session("other-tenant", 8L, 2L);
        handler.afterConnectionEstablished(recipient);
        handler.afterConnectionEstablished(otherTenant);

        NotificationUnreadCountReader countReader = (tenantId, userId) -> 3L;
        new NotificationWebSocketListener(handler, countReader).onNotificationChanged(
                new NotificationUnreadChangedEvent(1L, 8L));

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(recipient).sendMessage(message.capture());
        assertEquals("{\"event\":\"notification\",\"unreadCount\":3}", message.getValue().getPayload());
        verify(otherTenant, never()).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession session(String id, Long userId, Long tenantId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("userId", userId, "tenantId", tenantId,
                "securityVersion", 0L, "expiresAt", System.currentTimeMillis() + 60_000L));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
