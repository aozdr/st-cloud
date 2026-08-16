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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 事件投递器（TASK-004）：监听 OutboxRelayEvent，在业务事务提交后读取 event_log 并投递 RocketMQ。
 * <p>
 * 仅当 rocketmq.name-server 配置时启用；投递在后台线程异步执行，broker 慢/不可用不阻塞用户请求。
 * 投递成功标记 status=1，失败标记 status=2 交由 EventRetryTask 重投；发送前进程崩溃时行保持 PENDING，
 * 由 EventRetryTask 按超时兜底重投。
 * 事务回滚时监听不触发（AFTER_COMMIT），因此不会投递未提交的事件。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RequiredArgsConstructor
public class EventRelay {

    /** 后台投递线程池（daemon）：日志处理与用户操作完全解耦 */
    private static final ExecutorService RELAY_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "event-relay");
        t.setDaemon(true);
        return t;
    });

    private final EventLogMapper eventLogMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void relay(OutboxRelayEvent event) {
        log.debug("事件投递任务入队: id={}", event.getEventLogId());
        RELAY_EXECUTOR.execute(() -> doRelay(event.getEventLogId()));
    }

    private void doRelay(Long eventLogId) {
        try {
            EventLog outbox = eventLogMapper.selectById(eventLogId);
            if (outbox == null || outbox.getStatus() == EventOutboxStatus.SENT.getCode()) {
                log.warn("事件投递跳过: id={}, outbox={}", eventLogId, outbox == null ? "null" : "status=" + outbox.getStatus());
                // 行已被清理或已投递（重复事件），忽略
                return;
            }
            EventMessage message = objectMapper.readValue(outbox.getPayload(), EventMessage.class);
            log.debug("事件投递开始: id={}, type={}", eventLogId, topicOf(outbox.getEventType()));
            rocketMQTemplate.syncSend(topicOf(outbox.getEventType()), message);
            eventLogMapper.markSent(outbox.getId());
            log.debug("事件投递成功: id={}, type={}", outbox.getId(), outbox.getEventType());
        } catch (Exception e) {
            // 投递失败标记重投，由定时任务补偿；不向调用方抛出，避免影响已提交的业务事务
            eventLogMapper.markFailed(eventLogId);
            log.error("事件投递失败: id={}, error={}", eventLogId, e.getMessage(), e);
        }
    }

    /** 事件类型即主题：FILE_INDEX / SYNC_CHANGE */
    private String topicOf(String eventType) {
        return eventType;
    }
}
