package com.stcloud.team.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.event.NotificationUnreadChangedEvent;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.mapper.FileWatchMapper;
import com.stcloud.team.mapper.NotificationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 投递前核权、通用提醒和通知幂等测试。 */
@ExtendWith(MockitoExtension.class)
class FileWatchDeliveryProcessorTest {

    @Mock
    private FileWatchDeliveryMapper deliveryMapper;
    @Mock
    private FileWatchMapper watchMapper;
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private FileWatchAccessService accessService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private FileWatchDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FileWatchDeliveryProcessor(deliveryMapper, watchMapper, notificationMapper,
                accessService, new ObjectMapper().findAndRegisterModules(), eventPublisher);
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        when(accessService.isActiveUser(1L, 8L)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void repeatedDeliveryUsesExistingNotificationAndOnlyMarksSent() {
        FileWatchDelivery delivery = delivery(9001L, 8L, 100L, "UPDATE", "[11]");
        FileWatch watch = watch(11L, 8L, 100L);
        FileNode eventNode = node(100L, 1L, 10L, 1);
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9001L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9001L)).thenReturn(1);
        when(watchMapper.findForDelivery(eq(1L), eq(8L), anyCollection())).thenReturn(List.of(watch));
        when(accessService.findNode(1L, 100L)).thenReturn(eventNode);
        when(accessService.canViewCurrent(1L, 8L, eventNode)).thenReturn(true);
        when(accessService.findNode(1L, 100L)).thenReturn(eventNode);
        when(notificationMapper.findByTenantUserEvent(1L, 8L, delivery.getEventId())).thenReturn(existingNotification());
        when(deliveryMapper.markSent(1L, 9001L)).thenReturn(1);

        processor.processOne(1L, 9001L);

        verify(notificationMapper, never()).insert(any(Notification.class));
        verify(deliveryMapper).markSent(1L, 9001L);
    }

    @Test
    void canceledSubscriptionSuppressesDeliveryWithoutRetry() {
        FileWatchDelivery delivery = delivery(9002L, 8L, 100L, "UPDATE", "[12]");
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9002L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9002L)).thenReturn(1);
        when(watchMapper.findForDelivery(eq(1L), eq(8L), anyCollection())).thenReturn(List.of());
        when(deliveryMapper.markSuppressed(1L, 9002L)).thenReturn(1);

        processor.processOne(1L, 9002L);

        verify(deliveryMapper).markSuppressed(1L, 9002L);
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void invalidRecipientUserIsSuppressedBeforeSubscriptionLookup() {
        FileWatchDelivery delivery = delivery(9006L, 8L, 100L, "UPDATE", "[12]");
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9006L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9006L)).thenReturn(1);
        when(accessService.isActiveUser(1L, 8L)).thenReturn(false);
        when(deliveryMapper.markSuppressed(1L, 9006L)).thenReturn(1);

        processor.processOne(1L, 9006L);

        verify(accessService).isActiveUser(1L, 8L);
        verify(watchMapper, never()).findForDelivery(eq(1L), eq(8L), anyCollection());
        verify(deliveryMapper).markSuppressed(1L, 9006L);
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void directoryMoveWithInvisibleRootSendsGenericNotificationForVisibleDescendant() {
        FileWatchDelivery delivery = delivery(9003L, 8L, 200L, "MOVE", "[13]");
        FileWatch watch = watch(13L, 8L, 201L);
        FileNode movedRoot = node(200L, 1L, 10L, 0);
        FileNode watchedChild = node(201L, 1L, 200L, 1);
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9003L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9003L)).thenReturn(1);
        when(watchMapper.findForDelivery(eq(1L), eq(8L), anyCollection())).thenReturn(List.of(watch));
        when(accessService.findNode(1L, 200L)).thenReturn(movedRoot);
        when(accessService.canViewCurrent(1L, 8L, movedRoot)).thenReturn(false);
        when(accessService.findNode(1L, 201L)).thenReturn(watchedChild);
        when(accessService.canViewMovedDescendant(1L, 8L, movedRoot, watchedChild)).thenReturn(true);
        when(notificationMapper.findByTenantUserEvent(1L, 8L, delivery.getEventId())).thenReturn(null);
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
        when(deliveryMapper.markSent(1L, 9003L)).thenReturn(1);

        processor.processOne(1L, 9003L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals("关注内容已移动", captor.getValue().getTitle());
        assertEquals(null, captor.getValue().getContent());
        verify(eventPublisher).publishEvent(new NotificationUnreadChangedEvent(1L, 8L));
        verify(deliveryMapper).markSent(1L, 9003L);
    }

    @Test
    void actorDoesNotReceiveOwnNotification() {
        FileWatchDelivery delivery = delivery(9004L, 8L, 100L, "UPDATE", "[11]");
        delivery.setActorId(8L);
        FileWatch watch = watch(11L, 8L, 100L);
        FileNode eventNode = node(100L, 1L, 10L, 1);
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9004L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9004L)).thenReturn(1);
        when(watchMapper.findForDelivery(eq(1L), eq(8L), anyCollection())).thenReturn(List.of(watch));
        when(accessService.findNode(1L, 100L)).thenReturn(eventNode);
        when(accessService.canViewCurrent(1L, 8L, eventNode)).thenReturn(true);
        when(deliveryMapper.markSuppressed(1L, 9004L)).thenReturn(1);

        processor.processOne(1L, 9004L);

        verify(deliveryMapper).markSuppressed(1L, 9004L);
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void databaseFailurePropagatesForRetryInsteadOfSuppressing() {
        FileWatchDelivery delivery = delivery(9005L, 8L, 100L, "UPDATE", "[11]");
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 9005L)).thenReturn(delivery);
        when(deliveryMapper.markProcessing(1L, 9005L)).thenReturn(1);
        when(watchMapper.findForDelivery(eq(1L), eq(8L), anyCollection()))
                .thenThrow(new IllegalStateException("secret/path/value"));

        assertThrows(IllegalStateException.class, () -> processor.processOne(1L, 9005L));
        verify(deliveryMapper, never()).markSuppressed(1L, 9005L);
        verify(deliveryMapper, never()).markSent(1L, 9005L);
    }

    private FileWatchDelivery delivery(long eventId, long userId, long nodeId,
                                       String changeType, String watchIds) {
        FileWatchDelivery delivery = new FileWatchDelivery();
        delivery.setId(eventId);
        delivery.setTenantId(1L);
        delivery.setEventId(eventId + 10000L);
        delivery.setUserId(userId);
        delivery.setNodeId(nodeId);
        delivery.setChangeType(changeType);
        delivery.setWatchIds(watchIds);
        delivery.setPayload("{}");
        delivery.setStatus(0);
        delivery.setRetryCount(0);
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        delivery.setCreatedAt(LocalDateTime.now());
        delivery.setUpdatedAt(LocalDateTime.now());
        return delivery;
    }

    private FileWatch watch(long id, long userId, long nodeId) {
        FileWatch watch = new FileWatch();
        watch.setId(id);
        watch.setTenantId(1L);
        watch.setUserId(userId);
        watch.setNodeId(nodeId);
        return watch;
    }

    private FileNode node(long id, long tenantId, long parentId, int nodeType) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setSpaceId(10L);
        node.setNodeType(nodeType);
        node.setName("node-" + id);
        node.setPath("/node-" + id);
        node.setStatus(0);
        node.setHidden(0);
        node.setUploadStatus(2);
        node.setOwnerId(8L);
        return node;
    }

    private Notification existingNotification() {
        Notification notification = new Notification();
        notification.setId(300L);
        notification.setTenantId(1L);
        notification.setUserId(8L);
        notification.setEventId(9001L + 10000L);
        notification.setType("FILE_CHANGE");
        notification.setTitle("关注内容已更新");
        notification.setRead(0);
        return notification;
    }
}
