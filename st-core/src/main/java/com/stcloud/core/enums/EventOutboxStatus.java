package com.stcloud.core.enums;

import lombok.Getter;

/**
 * 事件 Outbox 状态（对应 event_log.status，TASK-004）。
 * 0-待投递 / 1-已投递 / 2-投递失败（交由重试任务补偿），与既有数据库语义保持一致。
 */
@Getter
public enum EventOutboxStatus {
    PENDING(0, "待投递"),
    SENT(1, "已投递"),
    FAILED(2, "投递失败");

    private final int code;
    private final String desc;

    EventOutboxStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static EventOutboxStatus fromCode(int code) {
        for (EventOutboxStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown EventOutboxStatus code: " + code);
    }
}
