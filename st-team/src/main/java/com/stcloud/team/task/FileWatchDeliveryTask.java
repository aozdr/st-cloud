package com.stcloud.team.task;

import com.stcloud.common.context.TenantContext;
import com.stcloud.team.entity.FileWatchDeliveryKey;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.service.FileWatchDeliveryProcessor;
import com.stcloud.team.service.FileWatchDeliveryRetryService;
import com.stcloud.team.service.FileWatchDeliveryReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** 全局关注投递调度器；不持有长事务，每条记录交给独立事务 Bean。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileWatchDeliveryTask {

    private static final int BATCH_SIZE = 100;

    private final FileWatchDeliveryMapper deliveryMapper;
    private final FileWatchDeliveryProcessor processor;
    private final FileWatchDeliveryRetryService retryService;

    /** 上传事务提交后立即异步消费；定时扫描仅补偿宕机、拒绝执行和失败重试。 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatchReady(FileWatchDeliveryReadyEvent event) {
        FileWatchDeliveryKey key = new FileWatchDeliveryKey();
        key.setId(event.deliveryId());
        key.setTenantId(event.tenantId());
        processKey(key);
    }

    @Scheduled(fixedDelay = 5000L)
    public void dispatch() {
        List<FileWatchDeliveryKey> keys;
        try {
            keys = deliveryMapper.selectDueKeys(BATCH_SIZE);
        } catch (RuntimeException e) {
            // 调度查询失败没有 delivery ID 可标记；下一个周期会继续扫描，不能伪造抑制。
            log.error("读取文件关注投递队列失败，等待下次调度: errorType={}", safeType(e));
            return;
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (FileWatchDeliveryKey key : keys) {
            processKey(key);
        }
    }

    private void processKey(FileWatchDeliveryKey key) {
        if (key == null || key.getTenantId() == null || key.getTenantId() <= 0
                || key.getId() == null || key.getId() <= 0) {
            log.warn("跳过字段不完整的文件关注投递队列键");
            return;
        }
        // 异步任务不能继承默认租户 1；每条记录显式恢复其持久化租户，finally 清理上下文。
        TenantContext.setTenantId(key.getTenantId());
        TenantContext.setTenantMode("SAAS");
        try {
            processor.processOne(key.getTenantId(), key.getId());
        } catch (RuntimeException failure) {
            try {
                retryService.recordFailure(key.getTenantId(), key.getId(), failure);
            } catch (RuntimeException retryFailure) {
                // 数据库仍不可用时保留原异常和租户/ID，下一轮扫描仍可继续；不把故障当作失权。
                log.error("记录文件关注投递重试状态失败: tenantId={}, deliveryId={}, errorType={}",
                        key.getTenantId(), key.getId(), safeType(retryFailure));
            }
            log.warn("文件关注投递失败，将按退避重试: tenantId={}, deliveryId={}, errorType={}",
                    key.getTenantId(), key.getId(), safeType(failure));
        } finally {
            TenantContext.clear();
        }
    }

    private String safeType(Throwable failure) {
        if (failure == null) {
            return "UNKNOWN";
        }
        String type = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "_");
        return type.isBlank() ? "UNKNOWN" : type;
    }
}
