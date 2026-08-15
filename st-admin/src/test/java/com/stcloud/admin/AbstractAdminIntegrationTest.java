package com.stcloud.admin;

import com.stcloud.admin.entity.AuditLog;
import com.stcloud.admin.mapper.AuditLogMapper;
import com.stcloud.admin.service.SpeedLimitManageService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.mapper.SysRateLimitMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * st-admin 集成测试基类。
 * <p>
 * 使用 H2 内存库 + 真实 MyBatis-Plus（含租户拦截器、自动填充），
 * 验证审计日志写入/查询与传输限速配置主路径。
 * {@code @Transactional} 保证每个测试方法执行后自动回滚。
 */
@SpringBootTest(classes = AdminTestApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractAdminIntegrationTest {

    @Autowired
    protected AuditLogMapper auditLogMapper;

    @Autowired
    protected SysRateLimitMapper rateLimitMapper;

    @Autowired
    protected SpeedLimitManageService speedLimitManageService;

    @BeforeEach
    void setUpTenantContext() {
        // 测试数据统一落在默认租户 1
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    /**
     * 插入一条审计日志（走真实 Mapper，验证 INSERT SQL 与字段落库）。
     */
    protected AuditLog insertAuditLog(String action, String username, Integer status) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(1L);
        auditLog.setUserId(100L);
        auditLog.setUsername(username);
        auditLog.setAction(action);
        auditLog.setTargetType("USER");
        auditLog.setTargetId(100L);
        auditLog.setTargetName(username);
        auditLog.setDetail("{\"summary\":\"测试操作\"}");
        auditLog.setIpAddress("127.0.0.1");
        auditLog.setStatus(status);
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(auditLog);
        return auditLog;
    }
}
