package com.stcloud.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.service.StorageService;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * V06：真实 InnoDB 行锁、服务代理、引用/配额及真实 Outbox 的可控交错。
 * 只允许显式独立测试库，保留唯一测试夹具供审计；不连接共享开发库，不实际投递 MQ/S3。
 */
@EnabledIfSystemProperty(named = "test.team.recycle.mysql.url",
        matches = "^jdbc:mysql://[^/]+/stcloud_team_recycle_20260930_env(?:\\?.*)?$")
@SpringBootTest(classes = CoreTestApplication.class, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${test.team.recycle.mysql.url}",
        "spring.datasource.username=${test.team.recycle.mysql.user:root}",
        "spring.datasource.password=${test.team.recycle.mysql.password}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=15",
        "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
@Import(TeamRecycleMysqlConcurrencyIntegrationTest.ConcurrencyConfig.class)
class TeamRecycleMysqlConcurrencyIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000L);
    private static final ThreadLocal<Waiter> ACTIVE_WAITER = new ThreadLocal<>();
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FileNodeMapper nodes;
    @Autowired private RecycleBinService recycle;
    @Autowired private StorageService storage;
    private ExecutorService workers;
    private long admin, owner, space, node, object;

    @BeforeEach
    void fixture() throws Exception {
        workers = Executors.newFixedThreadPool(2);
        admin = IDS.incrementAndGet(); owner = IDS.incrementAndGet();
        space = IDS.incrementAndGet(); node = IDS.incrementAndGet(); object = IDS.incrementAndGet();
        context();
        assertTrue(AopUtils.isAopProxy(recycle), "必须执行真实 Spring 服务事务代理");
        try (Connection baseline = dataSource.getConnection()) {
            assertEquals("MySQL", baseline.getMetaData().getDatabaseProductName());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, baseline.getTransactionIsolation());
            assertEquals("stcloud_team_recycle_20260930_env", baseline.getCatalog());
        }
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,password,status,storage_used,deleted) VALUES(?,1,?,'x',1,100,0)",
                admin, "recycle-" + admin);
        jdbc.update("INSERT INTO team_space(id,tenant_id,space_name,owner_id,storage_used,status,deleted) VALUES(?,1,?,?,32,1,0)",
                space, "race-" + space, owner);
        jdbc.update("INSERT INTO team_member(tenant_id,space_id,user_id,role,member_type,deleted) VALUES(1,?,?,0,0,0)", space, admin);
        jdbc.update("INSERT INTO file_object(id,tenant_id,md5,size,storage_path,ref_count,status,deleted) VALUES(?,1,?,32,?,1,0,0)",
                object, "race-md5-" + node, "race/" + node);
        FileNode file = new FileNode();
        file.setId(node); file.setTenantId(1L); file.setParentId(0L); file.setNodeType(1);
        file.setName("race-" + node + ".txt"); file.setPath("/" + file.getName());
        file.setFileSize(32L); file.setFileMd5("race-md5-" + node); file.setStoragePath("race/" + node);
        file.setObjectId(object); file.setStatus(1); file.setUploadStatus(2); file.setUploaderId(owner);
        file.setOwnerId(owner); file.setSpaceId(space); file.setRefCount(1); file.setVersion(0);
        assertEquals(1, nodes.insert(file));
        reset(storage);
    }

    @AfterEach
    void releaseThreads() throws Exception {
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "必须释放测试线程及锁连接");
        }
        UserContext.clear(); TenantContext.clear(); ACTIVE_WAITER.remove();
    }

    @ParameterizedTest
    @ValueSource(strings = {"restore", "delete"})
    void revocationCommittedWhileMutationWaitsRejectsStalePermission(String mutation) throws Exception {
        Waiter waiter = new Waiter();
        // 角色写路径的空间锁与节点锁由同一控制连接持有；节点锁只用于固定旧权限窗口。
        try (Connection revoker = lockSpaceAndNode()) {
            long blocker = connectionId(revoker);
            Future<Object> request = start(waiter, () -> mutate(mutation));
            awaitMysqlLockWait(waiter, blocker);
            assertFalse(request.isDone());
            try (var statement = revoker.prepareStatement("UPDATE team_member SET role=2 WHERE space_id=? AND user_id=? AND deleted=0")) {
                statement.setLong(1, space); statement.setLong(2, admin);
                assertEquals(1, statement.executeUpdate());
            }
            revoker.commit();
            BusinessException rejected = assertInstanceOf(BusinessException.class, request.get(10, TimeUnit.SECONDS),
                    "撤权已先提交，等锁后必须重读权限，不能使用锁前管理员快照");
            assertEquals(ResultCode.FORBIDDEN.getCode(), rejected.getCode());
        }
        assertEquals(2L, jdbc.queryForObject("SELECT role FROM team_member WHERE space_id=? AND user_id=?", Long.class, space, admin));
        assertKeptRecycled();
        assertEquals(0, outboxCount(null));
        verify(storage, never()).deleteObject(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"restore", "delete"})
    void restoreAndPermanentDeleteRecheckCurrentStateInBothLockOrders(String first) throws Exception {
        String second = first.equals("restore") ? "delete" : "restore";
        Waiter firstWaiter = new Waiter(), secondWaiter = new Waiter();
        Future<Object> winner, loser;
        // 真正观察第一请求在控制连接上等待后，才排入第二请求；并非依赖线程启动先后。
        try (Connection blocker = lockSpaceAndNode()) {
            long blockerId = connectionId(blocker);
            winner = start(firstWaiter, () -> mutate(first));
            awaitMysqlLockWait(firstWaiter, blockerId);
            loser = start(secondWaiter, () -> mutate(second));
            awaitMysqlLockWait(secondWaiter, blockerId);
            assertFalse(winner.isDone()); assertFalse(loser.isDone());
            blocker.commit();
            assertEquals("completed", winner.get(10, TimeUnit.SECONDS));
            Object lateResult = loser.get(10, TimeUnit.SECONDS);
            if (first.equals("restore")) {
                BusinessException rejected = assertInstanceOf(BusinessException.class, lateResult,
                        "等待期间节点已恢复，永久删除不能依据锁前回收态删除正常文件");
                assertEquals(ResultCode.BAD_REQUEST.getCode(), rejected.getCode());
            } else {
                BusinessException rejected = assertInstanceOf(BusinessException.class, lateResult,
                        "等待期间节点已删除，恢复不能复活节点或写 CREATE 事件");
                assertTrue(rejected.getCode() == ResultCode.FILE_NOT_FOUND.getCode()
                        || rejected.getCode() == ResultCode.CONFLICT.getCode());
            }
        }
        if (first.equals("restore")) {
            assertEquals(0, nodes.selectById(node).getStatus());
            assertEquals(32L, teamUsed()); assertEquals(1, objectRefs());
            assertEquals(2, outboxCount(null));
            assertEquals(1, outboxCount("FILE_INDEX")); assertEquals(1, outboxCount("SYNC_CHANGE"));
            assertEquals(0, outboxCount("PHYSICAL_DELETE"));
        } else {
            assertNull(nodes.selectById(node));
            assertEquals(0L, teamUsed()); assertEquals(0, objectRefs());
            assertEquals(2, outboxCount(null));
            assertEquals(1, outboxCount("FILE_INDEX")); assertEquals(1, outboxCount("PHYSICAL_DELETE"));
            assertEquals(0, outboxCount("SYNC_CHANGE"));
        }
        assertEquals(100L, personalUsed(), "团队回收不能扣减个人配额");
        // 真正的物理删除消费者不在本套中运行；检查可靠补偿事件且确认事务内没有直接 S3 调用。
        verify(storage, never()).deleteObject(anyString());
    }

    private Object mutate(String mutation) {
        if (mutation.equals("restore")) recycle.restore(List.of(node));
        else recycle.permanentDelete(List.of(node));
        return "completed";
    }

    private Future<Object> start(Waiter waiter, Callable<Object> action) {
        return workers.submit(() -> {
            context(); ACTIVE_WAITER.set(waiter);
            try { return action.call(); }
            catch (BusinessException rejected) { return rejected; }
            finally { ACTIVE_WAITER.remove(); UserContext.clear(); TenantContext.clear(); }
        });
    }

    private void context() {
        TenantContext.setTenantId(1L); TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(admin).tenantId(1L)
                .username("recycle-" + admin).build());
    }

    private Connection lockSpaceAndNode() throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            for (String table : List.of("team_space", "file_node")) {
                try (var lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? AND deleted=0 FOR UPDATE")) {
                    lock.setLong(1, table.equals("team_space") ? space : node);
                    try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                }
            }
            return connection;
        } catch (Throwable error) { connection.rollback(); connection.close(); throw error; }
    }

    private static long connectionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private void awaitMysqlLockWait(Waiter waiter, long blockerConnection) throws Exception {
        assertTrue(waiter.started.await(10, TimeUnit.SECONDS), "目标服务应实际执行 Mapper SQL");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID "
                            + "JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID "
                            + "WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=?", Integer.class,
                    waiter.connection.get(), blockerConnection);
            if (waiting != null && waiting > 0) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        fail("未观察到目标服务连接等待控制连接的真实 InnoDB 行锁");
    }

    private void assertKeptRecycled() {
        assertNotNull(nodes.selectById(node)); assertEquals(1, nodes.selectById(node).getStatus());
        assertEquals(32L, teamUsed()); assertEquals(100L, personalUsed()); assertEquals(1, objectRefs());
    }
    private long teamUsed() { return jdbc.queryForObject("SELECT storage_used FROM team_space WHERE id=?", Long.class, space); }
    private long personalUsed() { return jdbc.queryForObject("SELECT storage_used FROM sys_user WHERE id=?", Long.class, admin); }
    private int objectRefs() { return jdbc.queryForObject("SELECT ref_count FROM file_object WHERE id=?", Integer.class, object); }
    private int outboxCount(String type) {
        String sql = "SELECT COUNT(*) FROM event_log WHERE JSON_EXTRACT(payload,'$.fileNode.tenantId')=1 AND JSON_EXTRACT(payload,'$.fileNode.id')=?";
        return type == null ? jdbc.queryForObject(sql, Integer.class, node)
                : jdbc.queryForObject(sql + " AND event_type=?", Integer.class, node, type);
    }

    private static final class Waiter {
        final AtomicLong connection = new AtomicLong();
        final CountDownLatch started = new CountDownLatch(1);
    }

    /** 捕获的是服务事务实际交给 MyBatis 的连接，避免取池外连接伪造隔离/等待证据。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    public static class ConnectionObserver implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Waiter waiter = ACTIVE_WAITER.get();
            if (waiter != null) {
                Connection connection = (Connection) invocation.getArgs()[0];
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
                long actual = connectionId(connection);
                long previous = waiter.connection.getAndSet(actual);
                assertTrue(previous == 0 || previous == actual, "同一服务事务必须使用绑定连接");
                waiter.started.countDown();
            }
            return invocation.proceed();
        }
    }

    @TestConfiguration
    static class ConcurrencyConfig {
        // 在 @Value 注入完成后设置受控通道；不配置 RocketMQ 自动连接，仅保留真实待投递 Outbox。
        @Bean static BeanPostProcessor controlledOutboxChannel() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ReliableEventPublisher)
                        ReflectionTestUtils.setField(bean, "nameServer", "controlled-without-relay");
                    return bean;
                }
            };
        }
        @Bean Interceptor connectionObserver() { return new ConnectionObserver(); }
        @Bean StorageService storageService() { return mock(StorageService.class); }
        @Bean CloudStorageService cloudStorageService() { return mock(CloudStorageService.class); }
        @Bean ReliableEventPublisher reliableEventPublisher(EventLogMapper mapper) {
            return new ReliableEventPublisher(mapper, mock(ApplicationEventPublisher.class),
                    new ObjectMapper().findAndRegisterModules());
        }
        @Bean FileObjectService fileObjectService(FileObjectMapper mapper, StorageService storage) {
            FileObjectServiceImpl service = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(service, "fileObjectMapper", mapper);
            ReflectionTestUtils.setField(service, "storageService", storage);
            return service;
        }
        @Bean FileService fileService(FileNodeMapper nodes, UserQuotaMapper users, TeamStorageMapper teams,
                                    CloudStorageService cloud, ReliableEventPublisher events, FileObjectService objects) {
            FileServiceImpl service = new FileServiceImpl();
            ReflectionTestUtils.setField(service, "fileNodeMapper", nodes);
            ReflectionTestUtils.setField(service, "userQuotaMapper", users);
            ReflectionTestUtils.setField(service, "teamStorageMapper", teams);
            ReflectionTestUtils.setField(service, "cloudStorageService", cloud);
            ReflectionTestUtils.setField(service, "reliableEventPublisher", events);
            ReflectionTestUtils.setField(service, "fileObjectService", objects);
            return service;
        }
        @Bean RecycleBinService recycleBinService(FileNodeMapper nodes, UserQuotaMapper users, TeamStorageMapper teams,
                                                 FileService files, FileObjectService objects, ReliableEventPublisher events) {
            RecycleBinServiceImpl service = new RecycleBinServiceImpl();
            ReflectionTestUtils.setField(service, "fileNodeMapper", nodes);
            ReflectionTestUtils.setField(service, "userQuotaMapper", users);
            ReflectionTestUtils.setField(service, "teamStorageMapper", teams);
            ReflectionTestUtils.setField(service, "fileService", files);
            ReflectionTestUtils.setField(service, "fileObjectService", objects);
            ReflectionTestUtils.setField(service, "reliableEventPublisher", events);
            return service;
        }
    }
}
