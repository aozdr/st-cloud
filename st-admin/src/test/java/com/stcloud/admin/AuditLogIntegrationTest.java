package com.stcloud.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.admin.entity.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * st-admin 审计日志主路径集成测试：写入 / 按主键查询 / 按条件查询。
 */
@DisplayName("st-admin 审计日志集成测试")
class AuditLogIntegrationTest extends AbstractAdminIntegrationTest {

    @Test
    @DisplayName("审计日志写入后可按主键查询回读")
    void insertAndQueryById_roundTrip() {
        AuditLog inserted = insertAuditLog("LOGIN", "alice", 1);

        AuditLog found = auditLogMapper.selectById(inserted.getId());

        assertNotNull(found);
        assertEquals(inserted.getId(), found.getId());
        assertEquals("LOGIN", found.getAction());
        assertEquals("alice", found.getUsername());
        assertEquals("127.0.0.1", found.getIpAddress());
        assertEquals(1, found.getStatus());
    }

    @Test
    @DisplayName("按操作类型查询返回匹配的审计记录")
    void queryByAction_returnsMatchingLogs() {
        insertAuditLog("UPLOAD", "alice", 1);
        insertAuditLog("DOWNLOAD", "alice", 1);

        List<AuditLog> uploads = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getAction, "UPLOAD"));

        assertEquals(1, uploads.size());
        assertEquals("alice", uploads.get(0).getUsername());
    }

    @Test
    @DisplayName("按用户+动作组合条件查询隔离租户内记录")
    void queryByUsernameAndAction_combined() {
        insertAuditLog("LOGIN", "bob", 1);
        insertAuditLog("LOGIN", "alice", 1);

        List<AuditLog> logs = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLog>()
                        .eq(AuditLog::getUsername, "bob")
                        .eq(AuditLog::getAction, "LOGIN"));

        assertEquals(1, logs.size());
        assertEquals("bob", logs.get(0).getUsername());
    }

    @Test
    @DisplayName("失败状态与结构化 detail 完整落库")
    void failedStatusAndDetail_persisted() {
        AuditLog inserted = insertAuditLog("DELETE", "carol", 0);

        AuditLog found = auditLogMapper.selectById(inserted.getId());

        assertEquals(0, found.getStatus());
        assertTrue(found.getDetail().contains("summary"));
        assertEquals("127.0.0.1", found.getIpAddress());
    }
}
