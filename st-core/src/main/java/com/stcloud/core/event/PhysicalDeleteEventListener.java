package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 物理删除事件本地兜底监听器（事务边界治理 F4）：仅 MQ 未配置时生效，
 * 在业务事务提交后（AFTER_COMMIT）执行 S3 物理删除（幂等），
 * 避免事务回滚后仍删除仍被引用的物理对象；删除失败仅记录日志，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhysicalDeleteEventListener {

    private final FileObjectService fileObjectService;
    private final StorageService storageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPhysicalDelete(PhysicalDeleteEvent event) {
        FileNode node = event == null ? null : event.getFileNode();
        if (node == null) {
            log.warn("收到空物理删除事件，忽略");
            return;
        }
        try {
            if (node.getObjectId() != null) {
                fileObjectService.deletePhysical(node.getObjectId());
            } else if (node.getStoragePath() != null) {
                storageService.deleteObject(node.getStoragePath());
            } else {
                log.warn("物理删除事件缺少 objectId/storagePath，跳过: nodeId={}", node.getId());
            }
        } catch (Exception e) {
            log.error("本地兜底物理删除失败: nodeId={}, objectId={}, storagePath={}, error={}",
                    node.getId(), node.getObjectId(), node.getStoragePath(), e.getMessage());
        }
    }
}
