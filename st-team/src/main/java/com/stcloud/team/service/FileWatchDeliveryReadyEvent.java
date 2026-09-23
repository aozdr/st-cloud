package com.stcloud.team.service;

/** 文件写事务提交后唤醒投递；持久队列仍是失败重试和进程重启的事实源。 */
public record FileWatchDeliveryReadyEvent(Long tenantId, Long deliveryId) {
}
