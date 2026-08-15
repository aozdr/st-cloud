package com.stcloud.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.OutboxRelayEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.outbox.EventRelay;
import com.stcloud.core.outbox.EventRetryTask;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 事件 Outbox 集成测试（TASK-004）。
 * 验证：事务回滚不产生事件、MQ 未配置本地兜底、MQ 配置仅走 Outbox、事务提交后投递、
 * 投递失败标记重投与恢复、消费端按 event_log_id 幂等（sync_change_log 唯一键）。
 */
@Import(EventOutboxIntegrationTest.EventOutboxTestConfig.class)
class EventOutboxIntegrationTest extends AbstractIntegrationTest {

    /** 记录上下文内发布的所有事件，供断言 */
    static final List<ApplicationEvent> capturedEvents = new ArrayList<>();

    @TestConfiguration
    static class EventOutboxTestConfig {

        @Bean
        RocketMQTemplate rocketMQTemplate() {
            return Mockito.mock(RocketMQTemplate.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher(EventLogMapper eventLogMapper,
                                                       ApplicationEventPublisher eventPublisher,
                                                       ObjectMapper objectMapper) {
            return new ReliableEventPublisher(eventLogMapper, eventPublisher, objectMapper);
        }

        @Bean
        EventRelay eventRelay(EventLogMapper eventLogMapper, RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
            return new EventRelay(eventLogMapper, rocketMQTemplate, objectMapper);
        }

        @Bean
        EventRetryTask eventRetryTask(EventLogMapper eventLogMapper, RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
            return new EventRetryTask(eventLogMapper, rocketMQTemplate, objectMapper);
        }

        @Bean
        ApplicationListener<ApplicationEvent> eventCapture() {
            return capturedEvents::add;
        }
    }

    @Autowired
    private ReliableEventPublisher reliableEventPublisher;
    @Autowired
    private EventRelay eventRelay;
    @Autowired
    private EventRetryTask eventRetryTask;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private EventLogMapper eventLogMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        capturedEvents.clear();
        Mockito.reset(rocketMQTemplate);
    }

    /** 构造测试文件节点（内存对象，仅用于事件负载） */
    private FileNode buildNode(Long id) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(1L);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName("a.txt");
        node.setPath("/a.txt");
        node.setFileSize(1024L);
        node.setFileMd5("md5-test");
        node.setContentType("text/plain");
        node.setSuffix("txt");
        node.setStoragePath("t1/md5-test");
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setOwnerId(1L);
        node.setUploaderId(1L);
        node.setRefCount(1);
        node.setVersion(0);
        return node;
    }

    private void setMqEnabled(boolean enabled) {
        ReflectionTestUtils.setField(reliableEventPublisher, "nameServer", enabled ? "127.0.0.1:9876" : "");
    }

    private OutboxRelayEvent lastOutboxRelayEvent() {
        for (int i = capturedEvents.size() - 1; i >= 0; i--) {
            if (capturedEvents.get(i) instanceof OutboxRelayEvent relay) {
                return relay;
            }
        }
        return null;
    }

    /** 事务回滚不产生事件：REQUIRES_NEW 内发布后抛异常回滚，event_log 无新增行 */
    @Test
    void rollback_doesNotProduceEvent() {
        setMqEnabled(false);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> {
            reliableEventPublisher.publishFileIndex(buildNode(9001L), FileIndexEvent.ActionType.INDEX);
            throw new RuntimeException("触发回滚");
        }));
        assertEquals(0L, eventLogMapper.selectCount(null), "回滚后不应产生 Outbox 事件");
        // 本地兜底事件发布过，但未提交
        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof FileIndexEvent));
    }

    /** MQ 未配置：事务内保留本地 ApplicationEvent 兜底，且 Outbox 行仍写入 */
    @Test
    void mqDisabled_publishesLocalFallback_andWritesOutbox() {
        setMqEnabled(false);
        reliableEventPublisher.publishFileIndex(buildNode(9002L), FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(buildNode(9003L), SyncChangeEvent.ChangeType.RENAME, "/old.txt");

        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof FileIndexEvent), "MQ 关闭时应发布本地索引事件");
        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof SyncChangeEvent), "MQ 关闭时应发布本地同步事件");
        assertEquals(2L, eventLogMapper.selectCount(null), "无论通道如何，Outbox 行都应写入");
        // 本地兜底模式下不应发布 OutboxRelayEvent（无投递器监听）
        assertNull(lastOutboxRelayEvent());
    }

    /** MQ 配置：仅落 Outbox 并发布 OutboxRelayEvent，不发布本地业务事件（避免双通道重复消费） */
    @Test
    void mqEnabled_skipsLocalEvent_onlyOutbox() {
        setMqEnabled(true);
        reliableEventPublisher.publishFileIndex(buildNode(9004L), FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(buildNode(9005L), SyncChangeEvent.ChangeType.CREATE);

        assertTrue(capturedEvents.stream().noneMatch(e -> e instanceof FileIndexEvent), "MQ 开启时不应发布本地索引事件");
        assertTrue(capturedEvents.stream().noneMatch(e -> e instanceof SyncChangeEvent), "MQ 开启时不应发布本地同步事件");
        assertEquals(2L, eventLogMapper.selectCount(null));
        assertNotNull(lastOutboxRelayEvent());
    }

    /** 事务提交后由 EventRelay 投递 RocketMQ 并标记已投递 */
    @Test
    void relay_sendsAfterCommit() throws Exception {
        setMqEnabled(true);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> {
            reliableEventPublisher.publishFileIndex(buildNode(9006L), FileIndexEvent.ActionType.INDEX);
        });
        // AFTER_COMMIT 同步触发：投递一次、标记已投递
        verify(rocketMQTemplate, times(1)).syncSend(eq("FILE_INDEX"), any(EventMessage.class));
        OutboxRelayEvent relay = lastOutboxRelayEvent();
        assertNotNull(relay);
        EventLog outbox = eventLogMapper.selectById(relay.getEventLogId());
        assertNotNull(outbox);
        assertEquals(1, outbox.getStatus(), "投递成功后应标记 status=1");
        // 负载可反序列化且字段一致
        EventMessage parsed = objectMapper.readValue(outbox.getPayload(), EventMessage.class);
        assertEquals(9006L, parsed.getFileNode().getId());
        assertEquals("INDEX", parsed.getActionType());
        assertEquals("FILE_INDEX", parsed.getEventType());
        assertEquals(relay.getEventLogId(), parsed.getEventLogId());
        assertEquals("/a.txt", parsed.getFileNode().toFileNode().getPath());
    }

    /** 投递失败标记 status=2，重投任务在 MQ 恢复后重发并标记已投递 */
    @Test
    void relayFailure_markedFailed_thenRetryRecovers() throws Exception {
        setMqEnabled(true);
        // 准备一条失败事件（status=2, retry_count=1）
        long eventLogId = 8001L;
        EventMessage message = EventMessage.fromFileIndex(buildNode(9007L), FileIndexEvent.ActionType.INDEX, eventLogId);
        EventLog row = new EventLog();
        row.setId(eventLogId);
        row.setEventType("FILE_INDEX");
        row.setStatus(2);
        row.setRetryCount(1);
        row.setPayload(objectMapper.writeValueAsString(message));
        eventLogMapper.insert(row);

        // MQ 不可用：重投失败，retry_count 递增
        when(rocketMQTemplate.syncSend(anyString(), any(EventMessage.class))).thenThrow(new RuntimeException("broker down"));
        eventRetryTask.retryFailedEvents();
        EventLog afterFail = eventLogMapper.selectById(eventLogId);
        assertEquals(2, afterFail.getStatus(), "重投失败应保持 status=2");
        assertEquals(2, afterFail.getRetryCount(), "重投失败应累加重试次数");

        // MQ 恢复：重投成功，标记 status=1
        doReturn(null).when(rocketMQTemplate).syncSend(anyString(), any(EventMessage.class));
        eventRetryTask.retryFailedEvents();
        EventLog afterOk = eventLogMapper.selectById(eventLogId);
        assertEquals(1, afterOk.getStatus(), "重投成功应标记 status=1");
        verify(rocketMQTemplate, times(2)).syncSend(eq("FILE_INDEX"), any(EventMessage.class));
    }
    /** 消费幂等：同一 eventLogId 重复写入 sync_change_log 被唯一键拦截，不产生重复日志 */
    @Test
    void syncChangeLog_idempotentByEventLogId() {
        String sql = "INSERT INTO sync_change_log (tenant_id, user_id, file_node_id, change_type, path, name, node_type, event_log_id) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, 1L, 1L, 100L, "CREATE", "/a.txt", "a.txt", 1, 8002L);
        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(sql, 1L, 1L, 100L, "CREATE", "/a.txt", "a.txt", 1, 8002L));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_change_log WHERE event_log_id = ?", Long.class, 8002L);
        assertEquals(1L, count, "同一事件日志ID只应有一条同步日志");
    }
}
