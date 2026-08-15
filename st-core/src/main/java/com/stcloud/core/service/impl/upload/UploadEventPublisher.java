package com.stcloud.core.service.impl.upload;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 上传事件发布器（TASK-002/004）：统一封装上传完成后的索引/同步事件发布，
 * 避免各上传路径（秒传/简单/合并）重复拼装事件；事件经 ReliableEventPublisher 走 Outbox 可靠通道。
 */
@Component
@RequiredArgsConstructor
public class UploadEventPublisher {

    private final ReliableEventPublisher reliableEventPublisher;

    /** 新建文件完成：索引 + 同步创建 */
    public void publishCreated(FileNode node) {
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.CREATE);
    }

    /** 更新文件完成（替换上传/版本恢复）：索引 + 同步更新 */
    public void publishUpdated(FileNode node) {
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.UPDATE);
    }
}
