package com.stcloud.auth;

import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.mapper.SysTenantMapper;
import com.stcloud.auth.mapper.SysUserRoleMapper;
import com.stcloud.auth.service.AuthService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/生产服务/真实 JWT，Redis 为受控输出；仅显式独立库可执行。 */
@EnabledIfSystemProperty(named = "test.review.mysql.url", matches = ".+stcloud_review_fixes_20260930.*")
@SpringBootTest(classes = AuthTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.review.mysql.url}",
        "spring.datasource.username=${test.review.mysql.user:root}",
        "spring.datasource.password=${test.review.mysql.password}",
        "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
class RegisterMysqlConcurrencyIntegrationTest {
    @Autowired AuthService auth;
    @Autowired JwtUtils jwt;
    @Autowired DataSource datasource;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired ValueOperations<String, String> refresh;
    @SpyBean SysTenantMapper tenant;
    @SpyBean SysUserRoleMapper userRoles;

    @org.junit.jupiter.api.BeforeEach
    void resetRefreshEvidence() { reset(refresh); }

    private RegisterRequest request(String suffix) {
        var request = new RegisterRequest();
        request.setUsername("review-" + suffix + "-" + System.nanoTime());
        request.setPassword("review-only-pass");
        return request;
    }

    @Test
    void waitingRegistrationCannotInheritRevokedPermissionAndRefreshIsOutsideTransaction() throws Exception {
        var attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            attempted.countDown();
            return sqlSession.getMapper(SysTenantMapper.class).lockSecurityWrites(1L);
        }).when(tenant).lockSecurityWrites(1L);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String key = invocation.getArgument(0);
            Long userId = Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
            try (Connection observer = datasource.getConnection(); var query = observer.prepareStatement("SELECT COUNT(*) FROM sys_user WHERE id=?")) {
                query.setLong(1, userId);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
            return null;
        }).when(refresh).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        var worker = Executors.newSingleThreadExecutor();
        try (Connection blocker = datasource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                statement.executeQuery("SELECT id FROM sys_tenant WHERE id=1 FOR UPDATE").close();
                var request = request("revoked");
                var pending = worker.submit(() -> {
                    TenantContext.setTenantId(1L);
                    try { return auth.register(request); } finally { TenantContext.clear(); }
                });
                assertTrue(attempted.await(10, TimeUnit.SECONDS)); assertFalse(pending.isDone());
                statement.executeUpdate("UPDATE sys_role_permission SET deleted=1 WHERE tenant_id=1 AND role_id=2 AND deleted=0");
                statement.executeUpdate("UPDATE sys_user SET security_version=security_version+1 WHERE tenant_id=1");
                blocker.commit();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertTrue(response.getPermissions().isEmpty());
                assertTrue(jwt.parseToken(response.getToken()).get("permissions", List.class).isEmpty());
                assertEquals(0L, ((Number) jwt.parseToken(response.getToken()).get("securityVersion")).longValue());
            }
        } finally {
            worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE sys_role_permission SET deleted=0 WHERE tenant_id=1 AND role_id=2");
            TenantContext.clear();
        }
    }

    @Test
    void disabledTenantDoesNotCreateUserOrRefresh() {
        var request = request("disabled");
        TenantContext.setTenantId(1L);
        jdbc.update("UPDATE sys_tenant SET status=0 WHERE id=1");
        try {
            assertThrows(BusinessException.class, () -> auth.register(request));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, request.getUsername()));
            verify(refresh, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        } finally { jdbc.update("UPDATE sys_tenant SET status=1 WHERE id=1"); TenantContext.clear(); }
    }

    @Test
    void failedRoleWriteRollsBackUserAndDoesNotIssueRefresh() {
        var request = request("rollback");
        TenantContext.setTenantId(1L);
        doThrow(new IllegalStateException("test rollback")).when(userRoles).insert(any(com.stcloud.auth.entity.SysUserRole.class));
        try {
            assertThrows(IllegalStateException.class, () -> auth.register(request));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, request.getUsername()));
            verify(refresh, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        } finally { TenantContext.clear(); }
    }
}
