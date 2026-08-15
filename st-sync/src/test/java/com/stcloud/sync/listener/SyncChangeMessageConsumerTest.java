package com.stcloud.sync.listener;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.ws.SyncPushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SyncChangeMessageConsumer 单元测试（TASK-001 / TASK-004）：覆盖新写成功、
 * 幂等跳过（eventLogId 命中）、唯一键冲突静默跳过、其它异常重抛、空消息忽略五条路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("同步变更消息消费者测试")
class SyncChangeMessageConsumerTest {

    @Mock
    private SyncChangeLogMapper syncChangeLogMapper;

    @Mock
    private SyncPushService syncPushService;

    @InjectMocks
    private SyncChangeMessageConsumer consumer;

    /** 构造携带 eventLogId 的 CREATE 消息 */
    private EventMessage buildMessage(Long eventLogId) {
        FileNode node = new FileNode();
        node.setId(100L);
        node.setTenantId(1L);
        node.setOwnerId(2L);
        node.setName("file.txt");
        node.setPath("/file.txt");
        node.setNodeType(1);
        node.setFileMd5("abc123");
        node.setFileSize(1024L);
        return EventMessage.fromSyncChange(node, SyncChangeEvent.ChangeType.CREATE, null, eventLogId);
    }

    @Test
    @DisplayName("新消息 - 写入变更日志并推送通知")
    void newMessage_insertsLogAndPushes() {
        EventMessage message = buildMessage(10L);
        when(syncChangeLogMapper.selectCount(any())).thenReturn(0L);

        consumer.onMessage(message);

        verify(syncChangeLogMapper).insert(any(SyncChangeLog.class));
        verify(syncPushService).pushChangeNotification(eq(2L), any());
    }

    @Test
    @DisplayName("已处理消息(eventLogId 命中) - 幂等跳过")
    void alreadyProcessedMessage_skips() {
        EventMessage message = buildMessage(10L);
        when(syncChangeLogMapper.selectCount(any())).thenReturn(1L);

        consumer.onMessage(message);

        verify(syncChangeLogMapper, never()).insert(any(SyncChangeLog.class));
        verify(syncPushService, never()).pushChangeNotification(any(), any());
    }

    @Test
    @DisplayName("唯一键冲突 - 幂等跳过，不重抛")
    void duplicateKeyException_skipsSilently() {
        EventMessage message = buildMessage(10L);
        when(syncChangeLogMapper.selectCount(any())).thenReturn(0L);
        when(syncChangeLogMapper.insert(any(SyncChangeLog.class)))
                .thenThrow(new DuplicateKeyException("uk_event_log_id conflict"));

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(syncPushService, never()).pushChangeNotification(any(), any());
    }

    @Test
    @DisplayName("其它异常 - 重抛触发 MQ 重投")
    void otherException_rethrows() {
        EventMessage message = buildMessage(10L);
        when(syncChangeLogMapper.selectCount(any())).thenReturn(0L);
        when(syncChangeLogMapper.insert(any(SyncChangeLog.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThrows(RuntimeException.class, () -> consumer.onMessage(message));

        verify(syncPushService, never()).pushChangeNotification(any(), any());
    }

    @Test
    @DisplayName("空消息 - 忽略")
    void nullMessage_ignored() {
        consumer.onMessage(null);
        verifyNoInteractions(syncChangeLogMapper);
        verifyNoInteractions(syncPushService);
    }
}