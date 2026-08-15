package com.stcloud.core.event;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.EventOutboxStatus;
import com.stcloud.core.mapper.EventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 可靠事件发布器（TASK-004）：业务事务内先写 event_log（Outbox），再决定投递通道。
 * <p>
 * 双通道兼容：
 * <ul>
 *   <li>RocketMQ 已配置（rocketmq.name-server 非空）：仅落 Outbox 并发布 OutboxRelayEvent，
 *       由 EventRelay 在事务提交后投递 RocketMQ，消费端执行（ES / 同步日志）；本地监听器不触发，避免重复消费。</li>
 *   <li>RocketMQ 未配置：事务内保留本地 ApplicationEvent 兜底，由原 @EventListener 监听器处理（降级不阻塞主流程）。</li>
 * </ul>
 * 事务回滚时 Outbox 行随事务一并回滚，因此不产生任何事件，满足「回滚即无事件」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableEventPublisher {

    private final EventLogMapper eventLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${rocketmq.name-server:}")
    private String nameServer;

    /** MQ 是否已配置：配置即走 Outbox + RocketMQ，本地事件仅作未配置时的兜底 */
    private boolean mqEnabled() {
        return StringUtils.hasText(nameServer);
    }

    /** 发布文件索引事件（INDEX / DELETE / UPDATE_META） */
    public void publishFileIndex(FileNode node, FileIndexEvent.ActionType action) {
        Long eventLogId = writeOutbox(EventMessage.fromFileIndex(node, action, null));
        if (mqEnabled()) {
            eventPublisher.publishEvent(new OutboxRelayEvent(this, eventLogId));
        } else {
            // MQ 未配置：事务内本地事件兜底，保持原监听链路可用；同时标记 Outbox 已投递（本地投递），供定期清理
            eventPublisher.publishEvent(new FileIndexEvent(this, node, action));
            eventLogMapper.markSent(eventLogId);
        }
    }

    /** 发布同步变更事件（CREATE / UPDATE / MOVE / RENAME / DELETE，无变更前路径） */
    public void publishSyncChange(FileNode node, SyncChangeEvent.ChangeType change) {
        publishSyncChange(node, change, null);
    }

    /** 发布同步变更事件（携带变更前路径，用于 MOVE / RENAME） */
    public void publishSyncChange(FileNode node, SyncChangeEvent.ChangeType change, String oldPath) {
        Long eventLogId = writeOutbox(EventMessage.fromSyncChange(node, change, oldPath, null));
        if (mqEnabled()) {
            eventPublisher.publishEvent(new OutboxRelayEvent(this, eventLogId));
        } else {
            // MQ 未配置：事务内本地事件兜底，保持原监听链路可用；同时标记 Outbox 已投递（本地投递），供定期清理
            eventPublisher.publishEvent(new SyncChangeEvent(this, node, change, oldPath));
            eventLogMapper.markSent(eventLogId);
        }
    }

    /**
     * 事务内写 Outbox 行：预生成雪花 ID（与 @TableId ASSIGN_ID 同一 IdWorker），
     * 事件日志ID 直接写入 payload 作为消费者幂等键，一次 INSERT 完成。
     */
    private Long writeOutbox(EventMessage message) {
        long eventLogId = IdWorker.getId();
        message.setEventLogId(eventLogId);
        EventLog outbox = new EventLog();
        outbox.setId(eventLogId);
        outbox.setEventType(message.getEventType());
        // Outbox 初始状态为待投递
        outbox.setStatus(EventOutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        try {
            outbox.setPayload(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            // 事件负载序列化失败属编程错误，直接抛出让事务回滚，避免静默丢事件
            throw new IllegalStateException("事件负载序列化失败: " + message.getEventType(), e);
        }
        eventLogMapper.insert(outbox);
        return eventLogId;
    }
}
