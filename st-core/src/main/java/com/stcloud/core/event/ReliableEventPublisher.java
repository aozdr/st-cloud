package com.stcloud.core.event;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.UserContext;
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
        FileNode eventNode = normalizeAvailableEventNodeTenant(node);
        if (eventNode == null || eventNode.getId() == null || eventNode.getId() <= 0
                || eventNode.getTenantId() == null || eventNode.getTenantId() <= 0) {
            throw new IllegalStateException("索引事件缺少可信节点或租户快照");
        }
        Long eventLogId = writeOutbox(EventMessage.fromFileIndex(eventNode, action, null));
        if (mqEnabled()) {
            eventPublisher.publishEvent(new OutboxRelayEvent(this, eventLogId));
        } else {
            // MQ 未配置：事务内本地事件兜底，保持原监听链路可用；同时标记 Outbox 已投递（本地投递），供定期清理
            eventPublisher.publishEvent(new FileIndexEvent(this, eventNode, action));
            eventLogMapper.markSent(eventLogId);
        }
    }

    /** 发布同步变更事件（CREATE / UPDATE / MOVE / RENAME / DELETE，无变更前路径） */
    public void publishSyncChange(FileNode node, SyncChangeEvent.ChangeType change) {
        publishSyncChange(node, change, null, null);
    }

    /** 发布同步变更事件（携带变更前路径，用于 MOVE / RENAME） */
    public void publishSyncChange(FileNode node, SyncChangeEvent.ChangeType change, String oldPath) {
        publishSyncChange(node, change, oldPath, null);
    }

    /** 发布带 MOVE 旧父节点的同步变更；事件捕获在 Outbox 分支选择前同步执行。 */
    public void publishSyncChange(FileNode node, SyncChangeEvent.ChangeType change, String oldPath,
                                  Long oldParentId) {
        FileNode eventNode = normalizeEventNodeTenant(node);
        Long actorId = resolveActorId(eventNode);
        Long eventLogId = writeOutbox(EventMessage.fromSyncChange(eventNode, change, oldPath,
                oldParentId, actorId, null));
        // 关注队列必须与文件写事务同提交：监听器仅做数据库匹配/入队，失败向外抛出使事务回滚。
        eventPublisher.publishEvent(new FileWatchCaptureEvent(this, eventLogId, eventNode, change,
                oldParentId, actorId, java.time.LocalDateTime.now()));
        if (mqEnabled()) {
            eventPublisher.publishEvent(new OutboxRelayEvent(this, eventLogId));
        } else {
            eventPublisher.publishEvent(new SyncChangeEvent(this, eventNode, change, oldPath,
                    eventLogId, actorId, oldParentId, java.time.LocalDateTime.now()));
            eventLogMapper.markSent(eventLogId);
        }
    }

    /**
     * 发布前补齐 ORM 插入后未回写到内存实体的租户字段。
     *
     * <p>这里只信任已认证的 UserContext；不能使用 TenantContext 的默认租户兜底，
     * 否则后台线程缺少上下文时可能把其他租户事件错误标记为租户 1。</p>
     */
    private FileNode normalizeEventNodeTenant(FileNode node) {
        if (node == null || node.getId() == null || node.getId() <= 0) {
            throw new IllegalArgumentException("同步事件缺少文件节点标识");
        }
        normalizeAvailableEventNodeTenant(node);
        Long nodeTenantId = node.getTenantId();
        Long authenticatedTenantId = UserContext.getTenantId();
        if (nodeTenantId == null || nodeTenantId <= 0) {
            throw new IllegalStateException("同步事件缺少可信租户上下文");
        }
        return node;
    }

    /**
     * 索引通常先于同步事件发布；在 ORM tenant 插件未回填内存节点时，索引快照也必须带上可信租户。
     * 已持久化的后台事件保留节点原租户；若请求主体与节点租户冲突，则在写 Outbox 前拒绝。
     */
    private FileNode normalizeAvailableEventNodeTenant(FileNode node) {
        if (node == null) {
            return null;
        }
        Long nodeTenantId = node.getTenantId();
        Long authenticatedTenantId = UserContext.getTenantId();
        if (nodeTenantId == null || nodeTenantId <= 0) {
            if (authenticatedTenantId != null && authenticatedTenantId > 0) {
                // 只使用已认证主体上下文；不能以异步线程的默认租户替代。
                node.setTenantId(authenticatedTenantId);
            }
            return node;
        }
        if (authenticatedTenantId != null && authenticatedTenantId > 0
                && !nodeTenantId.equals(authenticatedTenantId)) {
            throw new IllegalStateException("索引事件节点租户与认证上下文不一致");
        }
        return node;
    }

    /** 操作主体只能取已验证登录上下文，不能用文件 owner 伪造匿名回调。 */
    private Long resolveActorId(FileNode node) {
        Long actorId = UserContext.getUserId();
        Long contextTenantId = UserContext.getTenantId();
        Long nodeTenantId = node == null ? null : node.getTenantId();
        if (actorId == null || contextTenantId == null || nodeTenantId == null
                || !nodeTenantId.equals(contextTenantId)) {
            return null;
        }
        return actorId;
    }

    /**
     * 发布物理删除事件（事务边界治理 F4）：回收站永久删除引用归零时调用，事务内写 Outbox。
     * <p>
     * MQ 配置时提交后由 {@link com.stcloud.core.outbox.EventRelay} 投递 PHYSICAL_DELETE 主题，
     * 消费端异步删除 S3 物理对象；MQ 未配置时由本地 {@link PhysicalDeleteEventListener}
     * 在事务提交后（AFTER_COMMIT）兜底删除（幂等），保证单实例部署仍能清理。
     */
    public void publishPhysicalDelete(FileNode node) {
        Long eventLogId = writeOutbox(EventMessage.fromPhysicalDelete(node, null));
        if (mqEnabled()) {
            eventPublisher.publishEvent(new OutboxRelayEvent(this, eventLogId));
        } else {
            // MQ 未配置：提交后本地兜底删除，同时标记 Outbox 已投递（本地投递），供定期清理
            eventPublisher.publishEvent(new PhysicalDeleteEvent(this, node));
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
