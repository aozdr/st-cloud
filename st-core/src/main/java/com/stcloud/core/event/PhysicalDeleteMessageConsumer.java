package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 物理删除事件 MQ 消费者（事务边界治理 F4）：接收 Outbox 投递的 PHYSICAL_DELETE 消息，
 * 异步执行 S3 物理对象删除（幂等）。
 * <p>
 * 与本地 {@link PhysicalDeleteEventListener} 兜底监听器并存（MQ 配置时本消费者生效）。
 * 删除失败仅记录日志，由 event_log 的 status=2 重投机制补偿，不阻塞主流程。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = "PHYSICAL_DELETE", consumerGroup = "stcloud-core")
@RequiredArgsConstructor
public class PhysicalDeleteMessageConsumer implements RocketMQListener<EventMessage> {

    private final FileObjectService fileObjectService;
    private final StorageService storageService;

    @Override
    public void onMessage(EventMessage message) {
        if (message == null || message.getFileNode() == null) {
            log.warn("收到空物理删除消息，忽略");
            return;
        }
        FileNode node = message.getFileNode().toFileNode();
        try {
            deletePhysicalQuietly(node);
        } catch (Exception e) {
            log.error("处理物理删除 MQ 消息失败: eventLogId={}, nodeId={}, objectId={}, storagePath={}, error={}",
                    message.getEventLogId(), node.getId(), node.getObjectId(), node.getStoragePath(), e.getMessage());
        }
    }

    /** 幂等删除：objectId 路径委托 fileObjectService（S3 删除 + 标记失效），旧数据按 storagePath 直接删 */
    private void deletePhysicalQuietly(FileNode node) {
        if (node.getObjectId() != null) {
            fileObjectService.deletePhysical(node.getObjectId());
        } else if (node.getStoragePath() != null) {
            storageService.deleteObject(node.getStoragePath());
        } else {
            log.warn("物理删除消息缺少 objectId/storagePath，跳过: nodeId={}", node.getId());
        }
    }
}
