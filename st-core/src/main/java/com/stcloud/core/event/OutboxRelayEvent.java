package com.stcloud.core.event;

import org.springframework.context.ApplicationEvent;

/**
 * Outbox 投递触发事件（TASK-004）：由 {@link ReliableEventPublisher} 在业务事务内发布，
 * {@link com.stcloud.core.outbox.EventRelay} 以 @TransactionalEventListener(AFTER_COMMIT)
 * 监听，在事务提交后读取 event_log 并投递 RocketMQ。仅 MQ 配置时发布。
 */
public class OutboxRelayEvent extends ApplicationEvent {

    private final Long eventLogId;

    public OutboxRelayEvent(Object source, Long eventLogId) {
        super(source);
        this.eventLogId = eventLogId;
    }

    public Long getEventLogId() {
        return eventLogId;
    }
}
