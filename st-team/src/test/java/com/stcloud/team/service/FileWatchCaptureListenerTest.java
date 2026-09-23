package com.stcloud.team.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileWatchCaptureEvent;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.FileWatchMatch;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.mapper.FileWatchMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文件关注捕获的事务边界、祖先聚合和目录后代匹配测试。 */
@ExtendWith(MockitoExtension.class)
class FileWatchCaptureListenerTest {

    @Mock
    private FileWatchMapper watchMapper;
    @Mock
    private FileWatchDeliveryMapper deliveryMapper;
    @Mock
    private FileNodeMapper nodeMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private FileWatchCaptureListener listener;

    @BeforeEach
    void setUp() {
        listener = new FileWatchCaptureListener(watchMapper, deliveryMapper, nodeMapper,
                new ObjectMapper().findAndRegisterModules(), eventPublisher);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void captureOutsideDatabaseTransactionFailsClosed() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        FileWatchCaptureEvent event = event(node(100L, 1L, 0L, 10L, 1),
                SyncChangeEvent.ChangeType.UPDATE, 9001L, null);

        assertThrows(IllegalStateException.class, () -> listener.capture(event));
        verify(watchMapper, never()).findMatches(anyLong(), any(), anyCollection());
        verify(deliveryMapper, never()).insert(any(FileWatchDelivery.class));
    }

    @Test
    void captureAggregatesAncestorSubscriptionsAndSkipsActor() {
        FileNode changed = node(100L, 1L, 20L, 10L, 1);
        FileNode parent = node(20L, 1L, 0L, 10L, 0);
        when(nodeMapper.selectByTenantAndId(1L, 100L)).thenReturn(changed);
        when(nodeMapper.selectByTenantAndId(1L, 20L)).thenReturn(parent);

        FileWatchMatch actorMatch = match(11L, 7L, 100L);
        FileWatchMatch recipientMatch = match(12L, 8L, 20L);
        when(watchMapper.findMatches(eq(1L), eq(10L), anyCollection()))
                .thenReturn(List.of(actorMatch, recipientMatch));

        FileWatchCaptureEvent event = event(changed, SyncChangeEvent.ChangeType.UPDATE,
                9002L, 7L);
        listener.capture(event);

        ArgumentCaptor<FileWatchDelivery> captor = ArgumentCaptor.forClass(FileWatchDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        FileWatchDelivery delivery = captor.getValue();
        assertEquals(1L, delivery.getTenantId());
        assertEquals(9002L, delivery.getEventId());
        assertEquals(8L, delivery.getUserId());
        assertEquals("[12]", delivery.getWatchIds());
        assertEquals(0, delivery.getStatus());
        verify(eventPublisher).publishEvent(new FileWatchDeliveryReadyEvent(1L, delivery.getId()));
    }

    @Test
    void movingFolderAlsoMatchesDirectDescendantSubscriptions() {
        FileNode movedFolder = node(200L, 1L, 30L, 10L, 0);
        FileNode oldParent = node(30L, 1L, 0L, 10L, 0);
        FileNode child = node(201L, 1L, 200L, 10L, 1);
        when(nodeMapper.selectByTenantAndId(1L, 200L)).thenReturn(movedFolder);
        when(nodeMapper.selectByTenantAndId(1L, 30L)).thenReturn(oldParent);
        when(watchMapper.findMatches(eq(1L), eq(10L), anyCollection()))
                .thenReturn(List.of())
                .thenReturn(List.of(match(13L, 8L, 201L)));
        when(watchMapper.hasAnyInScope(1L, 10L)).thenReturn(true);
        when(nodeMapper.selectChildrenByTenantSpace(eq(1L), eq(10L), anyCollection()))
                .thenReturn(List.of(child))
                .thenReturn(List.of());

        listener.capture(event(movedFolder, SyncChangeEvent.ChangeType.MOVE,
                9003L, 7L, 30L));

        ArgumentCaptor<FileWatchDelivery> captor = ArgumentCaptor.forClass(FileWatchDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals(8L, captor.getValue().getUserId());
        assertEquals("[13]", captor.getValue().getWatchIds());
    }

    @Test
    void brokenAncestorChainAbortsCapture() {
        FileNode changed = node(100L, 1L, 20L, 10L, 1);
        when(nodeMapper.selectByTenantAndId(1L, 100L)).thenReturn(changed);
        when(nodeMapper.selectByTenantAndId(1L, 20L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> listener.capture(
                event(changed, SyncChangeEvent.ChangeType.UPDATE, 9004L, null)));
        verify(deliveryMapper, never()).insert(any(FileWatchDelivery.class));
    }

    @Test
    void captureWithoutSubscriptionKeepsNewNodeCreationSuccessful() {
        FileNode changed = node(101L, 1L, 0L, null, 0);
        when(nodeMapper.selectByTenantAndId(1L, 101L)).thenReturn(changed);
        when(watchMapper.findMatches(eq(1L), eq(null), anyCollection())).thenReturn(List.of());

        listener.capture(event(changed, SyncChangeEvent.ChangeType.CREATE, 9005L, 7L));

        verify(deliveryMapper, never()).insert(any(FileWatchDelivery.class));
    }

    @Test
    void captureRejectsDatabaseSnapshotFromAnotherTenant() {
        FileNode eventNode = node(102L, 1L, 0L, 10L, 0);
        FileNode foreignNode = node(102L, 2L, 0L, 10L, 0);
        when(nodeMapper.selectByTenantAndId(1L, 102L)).thenReturn(foreignNode);

        assertThrows(IllegalStateException.class, () -> listener.capture(
                event(eventNode, SyncChangeEvent.ChangeType.CREATE, 9006L, 7L)));
        verify(watchMapper, never()).findMatches(anyLong(), any(), anyCollection());
        verify(deliveryMapper, never()).insert(any(FileWatchDelivery.class));
    }

    private FileWatchCaptureEvent event(FileNode node, SyncChangeEvent.ChangeType type,
                                        long eventId, Long actorId) {
        return event(node, type, eventId, actorId, null);
    }

    private FileWatchCaptureEvent event(FileNode node, SyncChangeEvent.ChangeType type,
                                        long eventId, Long actorId, Long oldParentId) {
        return new FileWatchCaptureEvent(this, eventId, node, type, oldParentId, actorId,
                LocalDateTime.of(2026, 9, 21, 1, 0));
    }

    private FileWatchMatch match(long id, long userId, long nodeId) {
        FileWatchMatch match = new FileWatchMatch();
        match.setId(id);
        match.setTenantId(1L);
        match.setUserId(userId);
        match.setNodeId(nodeId);
        return match;
    }

    private FileNode node(long id, long tenantId, long parentId, Long spaceId, int nodeType) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setSpaceId(spaceId);
        node.setNodeType(nodeType);
        node.setName("node-" + id);
        node.setPath("/node-" + id);
        node.setStatus(0);
        node.setHidden(0);
        node.setUploadStatus(2);
        node.setOwnerId(7L);
        node.setUploaderId(7L);
        return node;
    }
}
