package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 文件关注捕获事件。
 *
 * <p>该事件由文件写事务中的 {@link ReliableEventPublisher} 同步发布，携带不可变快照。
 * st-team 监听器只在同一数据库事务中匹配订阅并写入投递队列，不访问 S3、MQ、ES 或缓存。</p>
 */
@Getter
public final class FileWatchCaptureEvent extends ApplicationEvent {

    private final Long eventId;
    private final Long tenantId;
    private final Long actorId;
    private final SyncChangeEvent.ChangeType changeType;
    private final EventMessage.FileNodeSnapshot nodeSnapshot;
    private final Long oldParentId;
    private final LocalDateTime occurredAt;

    public FileWatchCaptureEvent(Object source, Long eventId, FileNode node,
                                 SyncChangeEvent.ChangeType changeType, Long oldParentId,
                                 Long actorId, LocalDateTime occurredAt) {
        super(source);
        if (node == null || node.getId() == null || eventId == null || changeType == null) {
            throw new IllegalArgumentException("文件关注事件缺少必要快照字段");
        }
        this.eventId = eventId;
        this.tenantId = node.getTenantId();
        this.actorId = actorId;
        this.changeType = changeType;
        // 事件发布后业务对象仍可能被调用方继续修改，因此只保存快照，不持有可变 FileNode 引用。
        this.nodeSnapshot = EventMessage.FileNodeSnapshot.from(node);
        this.oldParentId = oldParentId;
        this.occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
    }
}
