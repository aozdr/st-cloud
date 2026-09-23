package com.stcloud.sync.ws;

import com.stcloud.common.event.NotificationUnreadChangedEvent;
import com.stcloud.common.event.NotificationUnreadCountReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 通知提交后立即向在线客户端推送未读数；正文仍由已鉴权的通知接口读取。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWebSocketListener {

    private final SyncWebSocketHandler webSocketHandler;
    private final NotificationUnreadCountReader unreadCountReader;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationChanged(NotificationUnreadChangedEvent event) {
        try {
            // 提交后读取最新值，避免并发通知使用写事务中的旧快照覆盖铃铛状态。
            long unreadCount = unreadCountReader.countUnread(event.tenantId(), event.userId());
            String message = "{\"event\":\"notification\",\"unreadCount\":" + unreadCount + "}";
            webSocketHandler.sendToTenantUser(event.tenantId(), event.userId(), message);
        } catch (RuntimeException e) {
            // 推送失败不影响已提交的站内通知；客户端定时对账会恢复铃铛状态。
            log.warn("通知未读数推送失败：tenantId={}, userId={}, errorType={}",
                    event.tenantId(), event.userId(), e.getClass().getSimpleName());
        }
    }
}
