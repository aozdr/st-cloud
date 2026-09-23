package com.stcloud.team.service;

import com.stcloud.common.context.TenantContext;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 文件关注投递失败后的独立短事务退避记录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileWatchDeliveryRetryService {

    static final int MAX_RETRY_COUNT = 10;
    static final int STATUS_RETRY = 2;
    static final int STATUS_FAILED = 5;

    private final FileWatchDeliveryMapper deliveryMapper;

    /** 原处理事务已回滚后再记录失败，避免失败标记跟着业务事务一起回滚。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long tenantId, Long deliveryId, Throwable failure) {
        if (tenantId == null || tenantId <= 0 || deliveryId == null || deliveryId <= 0
                || !tenantId.equals(TenantContext.getTenantId())) {
            throw new IllegalStateException("文件关注重试租户上下文不一致");
        }
        FileWatchDelivery delivery = deliveryMapper.selectByTenantIdForUpdate(tenantId, deliveryId);
        if (delivery == null || delivery.getStatus() == null
                || delivery.getStatus() == FileWatchDeliveryProcessor.STATUS_SENT
                || delivery.getStatus() == FileWatchDeliveryProcessor.STATUS_SUPPRESSED) {
            return;
        }
        int previous = delivery.getRetryCount() == null ? 0 : Math.max(0, delivery.getRetryCount());
        int next = Math.min(MAX_RETRY_COUNT, previous + 1);
        int status = next >= MAX_RETRY_COUNT ? STATUS_FAILED : STATUS_RETRY;
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds(next));
        String summary = sanitize(failure);
        int changed = deliveryMapper.recordFailure(tenantId, deliveryId, next, status,
                nextRetryAt, summary);
        if (changed == 0) {
            log.debug("文件关注失败状态未更新，可能已被其他实例完成: tenantId={}, deliveryId={}",
                    tenantId, deliveryId);
        }
    }

    static long backoffSeconds(int retryCount) {
        if (retryCount <= 1) {
            return 5L;
        }
        long value = 5L << Math.min(6, retryCount - 1);
        return Math.min(300L, value);
    }

    private String sanitize(Throwable failure) {
        if (failure == null) {
            return "UNKNOWN_FAILURE";
        }
        // 只记录受控异常类型，不落 Throwable.message；SQL 异常消息可能包含文件名、路径、参数或凭证。
        String type = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "_");
        if (type.isBlank()) {
            return "UNKNOWN_FAILURE";
        }
        return ("FAILURE_" + type).substring(0, Math.min(500, type.length() + 8));
    }
}
