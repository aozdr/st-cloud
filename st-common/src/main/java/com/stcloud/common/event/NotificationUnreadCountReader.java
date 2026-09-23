package com.stcloud.common.event;

/** WebSocket 推送端在通知提交后按租户与用户读取最新未读数。 */
public interface NotificationUnreadCountReader {
    long countUnread(Long tenantId, Long userId);
}
