package com.stcloud.core.task;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.mapper.TenantScanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 后台任务按已有租户目录扫描，禁用租户保留数据，异常不串到其它租户。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantTaskRunner {
    private final TenantScanMapper tenantScanMapper;

    @Value("${stcloud.tenant.mode:SAAS}")
    private String configuredMode = "SAAS";

    public void runForEachTenant(String taskName, Runnable tenantTask) {
        Long previousTenantId = TenantContext.getTenantIdOrNull();
        String previousMode = TenantContext.getTenantModeOrNull();
        try {
            // 私有云保持原有单次扫描，不因租户目录扩大执行范围。
            if ("PRIVATE".equals(previousMode != null ? previousMode : configuredMode)) {
                TenantContext.setTenantId(previousTenantId != null ? previousTenantId : 1L);
                TenantContext.setTenantMode("PRIVATE");
                tenantTask.run();
                return;
            }
            List<Long> tenantIds = tenantScanMapper.selectEnabledTenantIds();
            for (Long tenantId : tenantIds) {
                try {
                    // 每轮重新设置 ID 和模式，避免前一租户异常或回调改动污染下一轮。
                    TenantContext.setTenantId(tenantId);
                    TenantContext.setTenantMode("SAAS");
                    tenantTask.run();
                } catch (RuntimeException e) {
                    log.warn("后台任务租户执行失败: task={}, tenantId={}", taskName, tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
        } finally {
            // 精确恢复调用前的未设置状态；无请求上下文的调度线程不会留下租户信息。
            TenantContext.setTenantId(previousTenantId);
            TenantContext.setTenantMode(previousMode);
        }
    }
}
