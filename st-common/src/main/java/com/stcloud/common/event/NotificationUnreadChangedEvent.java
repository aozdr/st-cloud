package com.stcloud.common.event;

/** 通知事务提交后触发未读数推送；不包含通知正文或文件信息。 */
public record NotificationUnreadChangedEvent(Long tenantId, Long userId) {
}
