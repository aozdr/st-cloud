package com.stcloud.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.auth.entity.SysTenant;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.common.context.TenantContext;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 主库真实 Mapper 的鉴权版本与租户状态边界。 */
class UserSecurityServiceIntegrationTest extends AbstractAuthIntegrationTest {
    @Autowired
    private UserSecurityService securityService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Claims claims(SysUser user) {
        return jwtUtils.parseToken(jwtUtils.generateToken(user.getId(), 1L, user.getUsername(),
                List.of("user"), List.of("file:view"), 1, 0L));
    }

    private Claims changed(Claims source, String name, Object value) {
        Map<String, Object> values = new HashMap<>(source);
        if (value == null) values.remove(name);
        else values.put(name, value);
        return Jwts.claims().add(values).build();
    }

    @Test
    void authenticationWithoutTenantSnapshotDoesNotWarnOrCreateDefaultContext() {
        SysUser user = insertUser("tenant-snapshot", "pass123456", 1);
        Claims access = claims(user);
        TenantContext.setTenantId(null);
        Logger logger = (Logger) LoggerFactory.getLogger(TenantContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int request = 0; request < 20; request++) {
                assertTrue(securityService.isCurrentAccess(access));
                assertNull(TenantContext.getTenantIdOrNull());
                assertEquals("SAAS", TenantContext.getTenantMode());
            }
            assertFalse(securityService.isCurrentAccess(changed(access, "userId", Long.MAX_VALUE)));
            assertNull(TenantContext.getTenantIdOrNull());
            assertTrue(appender.list.isEmpty(), "认证快照读取不应触发缺租户告警");
            // 真正的默认租户解析仍保留告警，不因修复快照读取而屏蔽调用链遗漏。
            assertEquals(1L, TenantContext.getTenantId());
            assertEquals(1, appender.list.size());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void authenticationRestoresExplicitTenantAndMode() {
        SysUser user = insertUser("tenant-restore", "pass123456", 1);
        Claims access = claims(user);
        TenantContext.setTenantId(99L);
        TenantContext.setTenantMode("PRIVATE");
        assertTrue(securityService.isCurrentAccess(access));
        assertEquals(99L, TenantContext.getTenantIdOrNull());
        assertEquals("PRIVATE", TenantContext.getTenantMode());
    }

    @Test
    void malformedOrMissingVersionNeverAuthenticates() {
        SysUser user = insertUser("version-edge", "pass123456", 1);
        Claims access = claims(user);
        assertTrue(securityService.isCurrentAccess(access));
        for (Object invalid : new Object[]{null, -1L, new BigInteger("9223372036854775808"),
                0.5d, "0"}) {
            assertFalse(securityService.isCurrentAccess(changed(access, "securityVersion", invalid)),
                    "非法版本不应建立授权: " + invalid);
        }
        assertTrue(securityService.isCurrentAccess(access));
        assertFalse(securityService.isCurrentAccess(changed(access, "type", "refresh")),
                "Refresh 不可用作 Access");
    }

    @Test
    void wrongTenantDeletedOrDisabledUserAndTenantAreRejected() {
        SysUser user = insertUser("state-edge", "pass123456", 1);
        Claims access = claims(user);
        assertTrue(securityService.isCurrentAccess(access));
        assertFalse(securityService.isCurrentAccess(changed(access, "tenantId", 2L)));
        assertFalse(securityService.isCurrentAccess(changed(access, "userId", Long.MAX_VALUE)));

        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId()).set(SysUser::getStatus, 0));
        assertFalse(securityService.isCurrentAccess(access));
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId()).set(SysUser::getStatus, 1));
        assertTrue(securityService.isCurrentAccess(access));

        SysTenant tenant = tenantMapper.selectById(1L);
        tenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, 1L).set(SysTenant::getStatus, 0));
        assertFalse(securityService.isCurrentAccess(access));
        tenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, 1L).set(SysTenant::getStatus, tenant.getStatus()));
        assertTrue(securityService.isCurrentAccess(access));

        tenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, 1L).set(SysTenant::getDeleted, 1));
        assertFalse(securityService.isCurrentAccess(access));
        // 仅隔离 H2 夹具还原逻辑删除，验证下一种用户删除情形；测试事务结束会整体回滚。
        jdbcTemplate.update("UPDATE sys_tenant SET deleted = 0 WHERE id = 1");
        assertTrue(securityService.isCurrentAccess(access));

        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId()).set(SysUser::getDeleted, 1));
        assertFalse(securityService.isCurrentAccess(access));
        assertEquals(1L, TenantContext.getTenantId());
    }
}
