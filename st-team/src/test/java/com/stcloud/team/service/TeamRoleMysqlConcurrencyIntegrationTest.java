package com.stcloud.team.service;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.team.dto.CreateInviteRequest;
import com.stcloud.team.dto.CreateSpaceRequest;
import com.stcloud.team.dto.InviteMemberRequest;
import com.stcloud.team.dto.TeamRoleRequest;
import com.stcloud.team.dto.TeamInviteVO;
import com.stcloud.team.mapper.TeamSpaceMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.stcloud.team.TeamTestApplication;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

/** 使用显式指定的独立 MySQL 测试库；不允许默认连接共享开发库。 */
@EnabledIfSystemProperty(named = "test.review.mysql.url", matches = "^jdbc:mysql://[^/]+/stcloud_review_fixes_20260930(?:_[A-Za-z0-9]+)*(?:\\?.*)?$")
@SpringBootTest(classes = TeamTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.review.mysql.url}",
        "spring.datasource.username=${test.review.mysql.user:root}",
        "spring.datasource.password=${test.review.mysql.password}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.sql.init.mode=never"
})
class TeamRoleMysqlConcurrencyIntegrationTest extends TeamRoleConcurrencyIntegrationTest {
    @Autowired private DataSource dataSource;
    private static final AtomicLong MYSQL_IDS = new AtomicLong(System.currentTimeMillis() * 1000L);

    // 继承两种锁顺序下的成员/邀请/接受邀请矩阵以及提交/回滚权限隔离场景。

    @ParameterizedTest
    @ValueSource(strings = {"inviteMember", "updateMemberRole", "createInvite", "joinByCode",
            "createRole", "updateRole", "deleteRole", "removeMember", "leaveSpace", "setExternalMember", "setExternalConfig"})
    void team01ActualServiceTransactionUsesReadCommitted(String operation) throws Exception {
        long owner = MYSQL_IDS.incrementAndGet(), candidate = MYSQL_IDS.incrementAndGet();
        Long space = createMysqlFixture(owner, candidate);
        Long role = teamService.createRole(space, roleRequest("隔离级别角色")).getData().getId();
        Long member = null;
        TeamInviteVO invite = null;
        if (java.util.Set.of("updateMemberRole", "removeMember", "leaveSpace", "setExternalMember").contains(operation)) {
            var request = new InviteMemberRequest();
            request.setUserId(candidate);
            member = teamService.inviteMember(space, request).getData().getId();
        } else if ("joinByCode".equals(operation)) {
            invite = teamService.createInvite(space, new CreateInviteRequest()).getData();
        }

        // 池默认 RR，断言的是服务代理开启后的实际绑定连接，不能用注解或测试外层事务代替。
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(AopUtils.isAopProxy(teamService));
        try (Connection baseline = dataSource.getConnection()) {
            assertEquals("MySQL", baseline.getMetaData().getDatabaseProductName());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, baseline.getTransactionIsolation());
        }
        TeamSpaceMapper actual = sqlSession.getMapper(TeamSpaceMapper.class);
        AtomicInteger checked = new AtomicInteger();
        doAnswer(call -> {
            assertServiceReadCommitted();
            checked.incrementAndGet();
            return actual.lockRoleWrites(space);
        }).when(spaceLocks).lockRoleWrites(space);

        switch (operation) {
            case "inviteMember" -> {
                var request = new InviteMemberRequest();
                request.setUserId(candidate);
                request.setRole(role);
                teamService.inviteMember(space, request);
            }
            case "updateMemberRole" -> teamService.updateMemberRole(space, member, role);
            case "createInvite" -> {
                var request = new CreateInviteRequest();
                request.setRole(role);
                teamService.createInvite(space, request);
            }
            case "joinByCode" -> {
                setUpUser(candidate, 1L);
                teamService.joinByCode(invite.getInviteCode());
            }
            case "createRole" -> teamService.createRole(space, roleRequest("新建角色"));
            case "updateRole" -> teamService.updateRole(space, role, roleRequest("更新角色"));
            case "deleteRole" -> teamService.deleteRole(space, role);
            case "removeMember" -> teamService.removeMember(space, member);
            case "leaveSpace" -> { setUpUser(candidate, 1L); teamService.leaveSpace(space); }
            case "setExternalMember" -> {
                var request = new com.stcloud.team.dto.ExternalMemberRequest();
                request.setMemberType(1); request.setExpireAt(LocalDateTime.now().plusHours(1));
                teamService.setExternalMember(space, member, request);
            }
            case "setExternalConfig" -> teamService.setExternalConfig(space, false);
            default -> fail("未知服务入口: " + operation);
        }
        assertEquals(1, checked.get(), "必须在目标服务事务里实际经过空间锁");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "expire"})
    void team01InviteInvalidatedWhileWaitingForSpaceLockCannotJoin(String invalidation) throws Exception {
        long owner = MYSQL_IDS.incrementAndGet(), candidate = MYSQL_IDS.incrementAndGet();
        Long space = createMysqlFixture(owner, candidate);
        Long role = teamService.createRole(space, roleRequest("失效邀请角色")).getData().getId();
        var request = new CreateInviteRequest();
        request.setRole(role);
        TeamInviteVO invite = teamService.createInvite(space, request).getData();
        CountDownLatch attemptingLock = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        TeamSpaceMapper actual = sqlSession.getMapper(TeamSpaceMapper.class);
        doAnswer(call -> {
            assertServiceReadCommitted();
            waitingConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            attemptingLock.countDown();
            return actual.lockRoleWrites(space);
        }).when(spaceLocks).lockRoleWrites(space);

        var acceptor = Executors.newSingleThreadExecutor(r -> new Thread(r, "team-mysql-invite-waiter"));
        // 独立连接先持有真实 InnoDB 空间行锁；此时 joinByCode 已完成锁前邀请读取才会发出信号。
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (var lock = blocker.prepareStatement("SELECT id FROM team_space WHERE id=? AND deleted=0 FOR UPDATE")) {
                    lock.setLong(1, space);
                    try (var rows = lock.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                var join = acceptor.submit(() -> {
                    setUpUser(candidate, 1L);
                    try {
                        teamService.joinByCode(invite.getInviteCode());
                        return (Object) "joined";
                    } catch (BusinessException rejected) {
                        return (Object) rejected;
                    } finally {
                        UserContext.clear();
                        TenantContext.clear();
                    }
                });
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS), "邀请读取后应尝试空间锁");
                awaitMysqlLockWait(waitingConnection.get());
                assertFalse(join.isDone());

                setUpUser(owner, 1L);
                if ("revoke".equals(invalidation)) {
                    teamService.revokeInvite(space, invite.getId());
                    assertEquals(0, jdbc.queryForObject("SELECT status FROM team_invite WHERE id=?", Integer.class, invite.getId()));
                } else {
                    // 锁前邀请永久有效；等待期间提交截止时间，再等真实时钟越界，避免靠启动耗时碰运气。
                    LocalDateTime expireAt = LocalDateTime.now().withNano(0).plusSeconds(2);
                    assertEquals(1, jdbc.update("UPDATE team_invite SET expire_at=? WHERE id=?", Timestamp.valueOf(expireAt), invite.getId()));
                    long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!LocalDateTime.now().isAfter(expireAt) && System.nanoTime() < expiryDeadline) {
                        long millis = Math.max(1, Duration.between(LocalDateTime.now(), expireAt).toMillis() + 1);
                        TimeUnit.MILLISECONDS.sleep(Math.min(millis, 100));
                    }
                    assertTrue(LocalDateTime.now().isAfter(expireAt), "截止时间必须在持锁期间实际到达");
                    assertEquals(expireAt, jdbc.queryForObject("SELECT expire_at FROM team_invite WHERE id=?", Timestamp.class, invite.getId()).toLocalDateTime());
                }
                assertFalse(join.isDone(), "失效提交时接受邀请仍应等待空间锁");
                blocker.rollback();

                BusinessException rejected = assertInstanceOf(BusinessException.class, join.get(10, TimeUnit.SECONDS));
                assertEquals(("revoke".equals(invalidation) ? ResultCode.TEAM_INVITE_NOT_FOUND : ResultCode.TEAM_INVITE_EXPIRED).getCode(), rejected.getCode());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE space_id=? AND user_id=? AND deleted=0", Integer.class, space, candidate));

                // 失效邀请不再阻止删除角色，也不能留下仍生效的悬空角色引用。
                teamService.deleteRole(space, role);
                assertNull(teamRoleMapper.selectById(role));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE space_id=? AND role=? AND deleted=0", Integer.class, space, role));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM team_invite WHERE space_id=? AND role=? AND status=1 AND deleted=0 AND (expire_at IS NULL OR expire_at>?)",
                        Integer.class, space, role, Timestamp.valueOf(LocalDateTime.now())));
            } finally {
                blocker.rollback();
            }
        } finally {
            acceptor.shutdownNow();
            assertTrue(acceptor.awaitTermination(10, TimeUnit.SECONDS), "等待线程必须释放，不能遗留连接和行锁");
        }
    }

    private Long createMysqlFixture(long owner, long candidate) {
        setUpUser(owner, 1L);
        insertUser(owner, 1L, "mysql-owner-" + owner);
        insertUser(candidate, 1L, "mysql-candidate-" + candidate);
        var request = new CreateSpaceRequest();
        request.setSpaceName("mysql-team-" + owner);
        request.setStorageQuota(100000L);
        return teamService.createSpace(request).getData().getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"removeMember", "leaveSpace", "setExternalMember", "setExternalConfig"})
    void team02PermissionInvalidationWaitsForAuthoritativeSpaceLock(String operation) throws Exception {
        long owner = MYSQL_IDS.incrementAndGet(), candidate = MYSQL_IDS.incrementAndGet();
        Long space = createMysqlFixture(owner, candidate);
        var invitation = new InviteMemberRequest(); invitation.setUserId(candidate);
        Long member = teamService.inviteMember(space, invitation).getData().getId();
        TeamSpaceMapper actual = sqlSession.getMapper(TeamSpaceMapper.class);
        AtomicLong requestConnection = new AtomicLong();
        CountDownLatch reached = new CountDownLatch(1);
        doAnswer(call -> {
            assertServiceReadCommitted();
            requestConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            reached.countDown();
            return actual.lockRoleWrites(space);
        }).when(spaceLocks).lockRoleWrites(space);
        var worker = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM team_space WHERE id=? FOR UPDATE")) {
                statement.setLong(1, space); statement.executeQuery().close();
            }
            var pending = worker.submit(() -> {
                setUpUser("leaveSpace".equals(operation) ? candidate : owner, 1L);
                try {
                    switch (operation) {
                        case "removeMember" -> teamService.removeMember(space, member);
                        case "leaveSpace" -> teamService.leaveSpace(space);
                        case "setExternalMember" -> {
                            var request = new com.stcloud.team.dto.ExternalMemberRequest();
                            request.setMemberType(1); request.setExpireAt(LocalDateTime.now().minusSeconds(1));
                            teamService.setExternalMember(space, member, request);
                        }
                        case "setExternalConfig" -> teamService.setExternalConfig(space, false);
                    }
                } finally { UserContext.clear(); TenantContext.clear(); }
            });
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            awaitMysqlLockWait(requestConnection.get());
            assertFalse(pending.isDone(), "撤权入口必须等待回收决策持有的同一空间锁");
            blocker.commit(); pending.get(10, TimeUnit.SECONDS);
            if (operation.equals("removeMember") || operation.equals("leaveSpace")) assertNull(teamMemberMapper.selectById(member));
            else if (operation.equals("setExternalMember")) assertEquals(1, teamMemberMapper.selectById(member).getMemberType());
            else assertEquals(0, jdbc.queryForObject("SELECT allow_external FROM team_external_config WHERE space_id=?", Integer.class, space));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private TeamRoleRequest roleRequest(String name) {
        var request = new TeamRoleRequest();
        request.setName(name);
        request.setPermissions("{\"view\":true}");
        return request;
    }

    private void assertServiceReadCommitted() throws Exception {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "检查点必须位于实际服务事务内");
        assertTrue(TransactionSynchronizationManager.hasResource(dataSource), "连接必须绑定当前 Spring 服务事务");
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
            assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void awaitMysqlLockWait(long connectionId) throws InterruptedException {
        assertTrue(connectionId > 0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 查询真实事务状态，只有数据库确认 LOCK WAIT 后才提交撤销或等待过期。
        while (System.nanoTime() < deadline) {
            // performance_schema 按请求线程精确观察锁等待，避免 innodb_trx 事务快照短期缓存造成假阴性。
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID "
                    + "WHERE t.PROCESSLIST_ID=?", Integer.class, connectionId);
            if (Integer.valueOf(1).equals(waiting)) return;
            TimeUnit.MILLISECONDS.sleep(25);
        }
        fail("MySQL 未观测到接受邀请连接的 LOCK WAIT，不能视为并发场景证据");
    }
}
