package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.task.TenantTaskRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 H2/租户拦截器验证扫描覆盖两租户，禁用/删除租户保留；不执行删除。 */
@Import({FileServiceFlowIntegrationTest.FlowTestConfig.class, TenantTaskRunner.class})
class TenantRecycleScanIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TenantTaskRunner runner;
    @Autowired private RecycleBinService recycleBinService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void enumeratesEnabledTenantsAndKeepsRecycleRootsIsolated() {
        Map<Long, Long> rootByTenant = new LinkedHashMap<>();
        for (long tenantId = 9501; tenantId <= 9504; tenantId++) {
            jdbc.update("INSERT INTO sys_tenant(id,tenant_name,tenant_code,status,deleted) VALUES(?,?,?,?,?)",
                    tenantId, "scan-" + tenantId, "scan-" + tenantId, tenantId == 9503 ? 0 : 1, tenantId == 9504 ? 1 : 0);
            TenantContext.setTenantId(tenantId);
            FileNode root = insertFileNode(tenantId, tenantId, "expired-" + tenantId, 1);
            jdbc.update("UPDATE file_node SET updated_at='2000-01-01' WHERE id=?", root.getId());
            rootByTenant.put(tenantId, root.getId());
        }
        TenantContext.clear();
        Map<Long, List<Long>> scanned = new LinkedHashMap<>();
        runner.runForEachTenant("回收站只读测试", () -> scanned.put(TenantContext.getTenantIdOrNull(),
                recycleBinService.findExpiredRecycleRoots()));
        assertEquals(Map.of(9501L, List.of(rootByTenant.get(9501L)),
                9502L, List.of(rootByTenant.get(9502L))), scanned);
        assertNull(TenantContext.getTenantIdOrNull());
        assertNull(TenantContext.getTenantModeOrNull());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM file_node WHERE id IN (?,?,?,?) AND deleted=0",
                Integer.class, rootByTenant.values().toArray()));
    }
}
