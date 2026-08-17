package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import org.springframework.context.ApplicationEvent;

/**
 * 物理删除事件（事务边界治理 F4）：回收站永久删除引用归零时，在业务事务内发布，
 * 由本地 {@link PhysicalDeleteEventListener} 以 @TransactionalEventListener(AFTER_COMMIT)
 * 兜底执行 S3 物理删除。仅 MQ 未配置（无 rocketmq.name-server）的单实例部署使用；
 * MQ 配置时改走 Outbox + RocketMQ（PHYSICAL_DELETE 主题），本地监听不触发。
 */
public class PhysicalDeleteEvent extends ApplicationEvent {

    private final FileNode fileNode;

    public PhysicalDeleteEvent(Object source, FileNode fileNode) {
        super(source);
        this.fileNode = fileNode;
    }

    public FileNode getFileNode() {
        return fileNode;
    }
}
