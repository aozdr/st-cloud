package com.stcloud.team.entity;

import lombok.Data;

/** 全局后台调度仅读取 delivery ID 与租户，处理时再恢复正确租户上下文。 */
@Data
public class FileWatchDeliveryKey {
    private Long id;
    private Long tenantId;
}
