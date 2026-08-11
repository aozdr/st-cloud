package com.stcloud.sync.listener;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.ws.SyncPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 同步变更日志监听器
 * <p>
 * 监听 st-core 发布的 SyncChangeEvent，将文件变更写入 sync_change_log 表，
 * 并通过 WebSocket 向在线客户端推送变更通知，驱动近实时同步。
 * <p>
 * 变更日志的自增 id 作为同步游标，客户端通过 since=id 拉取增量变更。
 * 异步执行，不阻塞文件操作主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncChangeLogListener {

    private final SyncChangeLogMapper syncChangeLogMapper;
    private final SyncPushService syncPushService;

    @Async
    @EventListener
    public void onSyncChange(SyncChangeEvent event) {
        try {
            FileNode node = event.getFileNode();
            if (node == null || node.getId() == null) {
                return;
            }

            SyncChangeLog logEntry = new SyncChangeLog();
            logEntry.setTenantId(node.getTenantId() != null ? node.getTenantId() : TenantContext.getTenantId());
            // 以文件所有者作为同步目标用户；团队空间文件按所有者同步
            logEntry.setUserId(node.getOwnerId());
            logEntry.setFileNodeId(node.getId());
            logEntry.setChangeType(event.getChangeType().name());
            logEntry.setPath(node.getPath());
            logEntry.setOldPath(event.getOldPath());
            logEntry.setName(node.getName());
            logEntry.setNodeType(node.getNodeType());
            logEntry.setFileMd5(node.getFileMd5());
            logEntry.setFileSize(node.getFileSize() != null ? node.getFileSize() : 0L);

            syncChangeLogMapper.insert(logEntry);
            log.debug("同步变更日志已写入: type={}, nodeId={}, path={}, logId={}",
                    event.getChangeType(), node.getId(), node.getPath(), logEntry.getId());

            // 写入日志后立即推送 WebSocket 通知，触发客户端拉取增量
            syncPushService.pushChangeNotification(node.getOwnerId(), logEntry.getId());
        } catch (Exception e) {
            // 日志写入失败不影响文件操作主流程，仅记录错误
            log.error("写入同步变更日志失败: nodeId={}, type={}, error={}",
                    event.getFileNode() != null ? event.getFileNode().getId() : null,
                    event.getChangeType(), e.getMessage(), e);
        }
    }
}