package com.stcloud.core.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.enums.EventOutboxStatus;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.OutboxRelayEvent;
import com.stcloud.core.mapper.EventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事件投递器（TASK-004）：监听 OutboxRelayEvent，在业务事务提交后读取 event_log 并投递 RocketMQ。
 * <p>
 * 仅当 rocketmq.name-server 配置时启用；投递成功标记 status=1，失败标记 status=2 交由 EventRetryTask 重投。
 * 事务回滚时监听不触发（AFTER_COMMIT），因此不会投递未提交的事件。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RequiredArgsConstructor
public class EventRelay {

    private final EventLogMapper eventLogMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void relay(OutboxRelayEvent event) {
        EventLog outbox = eventLogMapper.selectById(event.getEventLogId());
        if (outbox == null || outbox.getStatus() == EventOutboxStatus.SENT.getCode()) {
            // 行已被清理或已投递（重复事件），忽略
            return;
        }
        try {
            EventMessage message = objectMapper.readValue(outbox.getPayload(), EventMessage.class);
            rocketMQTemplate.syncSend(topicOf(outbox.getEventType()), message);
            eventLogMapper.markSent(outbox.getId());
            log.debug("事件投递成功: id={}, type={}", outbox.getId(), outbox.getEventType());
        } catch (Exception e) {
            // 投递失败标记重投，由定时任务补偿；不向调用方抛出，避免影响已提交的业务事务
            eventLogMapper.markFailed(outbox.getId());
            log.error("事件投递失败: id={}, type={}, error={}", outbox.getId(), outbox.getEventType(), e.getMessage(), e);
        }
    }

    /** 事件类型即主题：FILE_INDEX / SYNC_CHANGE */
    private String topicOf(String eventType) {
        return eventType;
    }
}
