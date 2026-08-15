package com.stcloud.sync.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.ws.SyncPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.dao.DuplicateKeyException;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 同步变更消息 MQ 消费者（TASK-004）：接收 Outbox 投递的 SYNC_CHANGE 消息，
 * 将文件变更写入 sync_change_log 并推送 WebSocket 通知，与本地 @EventListener 监听器并存
 * （MQ 配置时本消费者生效，本地监听器兜底未配置场景）。
 * <p>
 * 幂等策略：MQ 消息携带 eventLogId，写入前先查 sync_change_log 是否已处理（唯一键 uk_event_log_id 兜底），
 * 重复投递不会产生重复变更日志，保证同步游标单调不重。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = "SYNC_CHANGE", consumerGroup = "stcloud-sync")
@RequiredArgsConstructor
public class SyncChangeMessageConsumer implements RocketMQListener<EventMessage> {

    private final SyncChangeLogMapper syncChangeLogMapper;
    private final SyncPushService syncPushService;

    @Override
    public void onMessage(EventMessage message) {
        if (message == null || message.getFileNode() == null || message.getFileNode().getId() == null
                || message.getChangeType() == null) {
            log.warn("收到空同步变更消息，忽略");
            return;
        }
        Long eventLogId = message.getEventLogId();
        if (eventLogId != null && alreadyProcessed(eventLogId)) {
            log.debug("同步变更消息已处理过，幂等跳过: eventLogId={}", eventLogId);
            return;
        }
        try {
            FileNode node = message.getFileNode().toFileNode();
            SyncChangeLog logEntry = new SyncChangeLog();
            logEntry.setTenantId(node.getTenantId() != null ? node.getTenantId() : TenantContext.getTenantId());
            // 以文件所有者作为同步目标用户；团队空间文件按所有者同步
            logEntry.setUserId(node.getOwnerId());
            logEntry.setFileNodeId(node.getId());
            logEntry.setChangeType(message.getChangeType());
            logEntry.setPath(node.getPath());
            logEntry.setOldPath(message.getOldPath());
            logEntry.setName(node.getName());
            logEntry.setNodeType(node.getNodeType());
            logEntry.setFileMd5(node.getFileMd5());
            logEntry.setFileSize(node.getFileSize() != null ? node.getFileSize() : 0L);
            logEntry.setEventLogId(eventLogId);

            syncChangeLogMapper.insert(logEntry);
            log.debug("同步变更日志已写入: type={}, nodeId={}, path={}, logId={}, eventLogId={}",
                    message.getChangeType(), node.getId(), node.getPath(), logEntry.getId(), eventLogId);

            // 写入日志后立即推送 WebSocket 通知，触发客户端拉取增量
            syncPushService.pushChangeNotification(node.getOwnerId(), logEntry.getId());
        } catch (DuplicateKeyException e) {
            // 唯一键冲突视为重复消息（并发竞态），幂等跳过，不触发重投
            log.debug("同步变更消息唯一键冲突，幂等跳过: nodeId={}, type={}, error={}",
                    message.getFileNode().getId(), message.getChangeType(), e.getMessage());
        } catch (Exception e) {
            // 其它异常重抛，交由 RocketMQ at-least-once 重投（eventLogId 幂等兜底，不产生重复日志）
            log.warn("写入同步变更日志失败，触发 MQ 重投: nodeId={}, type={}, error={}",
                    message.getFileNode().getId(), message.getChangeType(), e.getMessage());
            throw e;
        }
    }

    /** 幂等检查：该事件日志ID是否已写入同步日志 */
    private boolean alreadyProcessed(Long eventLogId) {
        Long count = syncChangeLogMapper.selectCount(new LambdaQueryWrapper<SyncChangeLog>()
                .eq(SyncChangeLog::getEventLogId, eventLogId));
        return count != null && count > 0;
    }
}
