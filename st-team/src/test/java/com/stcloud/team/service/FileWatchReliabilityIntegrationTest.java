package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.config.JacksonConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileWatchCaptureEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.mapper.FileWatchMapper;
import com.stcloud.team.mapper.NotificationMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件关注可靠性 H2 集成测试。
 *
 * <p>本类显式导入捕获、核权、投递和重试 Bean；测试数据通过真实 MyBatis-Plus Mapper
 * 写入 H2。类级别不包裹测试事务，所有需要验证边界的操作都由 TransactionTemplate
 * 建立真实 Spring 事务，避免把测试方法事务误当成文件写事务。</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JacksonConfig.class,
        TeamFileAccessPolicyImpl.class,
        FileWatchAccessService.class,
        FileWatchCaptureListener.class,
        FileWatchDeliveryProcessor.class,
        FileWatchDeliveryRetryService.class,
        FileWatchNotificationService.class
})
class FileWatchReliabilityIntegrationTest extends AbstractTeamIntegrationTest {

    @Autowired
    private FileWatchCaptureListener captureListener;

    @Autowired
    private FileWatchDeliveryProcessor deliveryProcessor;

    @Autowired
    private FileWatchDeliveryRetryService retryService;

    @Autowired
    private FileWatchAccessService accessService;

    @Autowired
    private FileWatchNotificationService notificationService;

    @Autowired
    private FileWatchMapper fileWatchMapper;

    @Autowired
    private FileWatchDeliveryMapper deliveryMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private TeamMemberMapper teamMemberMapper;

    @Autowired
    private TeamSpaceMapper teamSpaceMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private EventLogMapper eventLogMapper;

    @Test
    void fileWriteRollback_alsoRollsBackCapturedDelivery() {
        long tenantId = 4101L;
        long ownerId = 41011L;
        long watcherId = 41012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "rollback-owner");
        insertUser(watcherId, tenantId, "rollback-watcher");
        insertUser(41013L, tenantId, "rollback-actor");
        FileNode node = insertNode(tenantId, ownerId, null, "before.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        FileWatch watch = insertWatch(tenantId, watcherId, node.getId(), null);

        FileNode changed = copyNode(node);
        changed.setName("after.txt");
        changed.setPath("/after.txt");
        FileWatchCaptureEvent event = event(changed, 4101001L, SyncChangeEvent.ChangeType.UPDATE,
                41013L, null);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            // 模拟文件业务写入和同步事件捕获处于同一数据库事务。
            fileNodeMapper.updateById(changed);
            captureListener.capture(event);
            assertEquals(1, deliveryCount(tenantId));
            status.setRollbackOnly();
        });

        setTenant(tenantId);
        FileNode afterRollback = fileNodeMapper.selectById(node.getId());
        assertNotNull(afterRollback);
        assertEquals("before.txt", afterRollback.getName());
        assertEquals(0, deliveryCount(tenantId));
        assertNull(notificationMapper.findByTenantUserEvent(tenantId, watcherId, event.getEventId()));
        assertTrue(watch.getId() > 0);
    }

    @Test
    void newPersonalAndTeamNodes_useTrustedTenantSnapshotForWatchCapture() {
        long tenantId = 4151L;
        long otherTenantId = 4152L;
        long actorId = 41511L;
        long watcherId = 41512L;
        long spaceId = 415101L;
        setUpUser(actorId, tenantId);
        insertUser(actorId, tenantId, "new-node-actor");
        insertUser(watcherId, tenantId, "new-node-watcher");

        FileNode personal = insertNode(tenantId, actorId, null, "new-personal", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        FileNode personalMemoryNode = copyNode(personal);
        personalMemoryNode.setTenantId(null);

        List<FileWatchCaptureEvent> captures = new ArrayList<>();
        ReliableEventPublisher publisher = capturePublisher(captures);
        publishCommitted(publisher, personalMemoryNode, SyncChangeEvent.ChangeType.CREATE);
        assertEquals(tenantId, personalMemoryNode.getTenantId());
        assertEquals(tenantId, captures.get(0).getTenantId());
        assertEquals(0, deliveries(tenantId, captures.get(0).getEventId()).size(),
                "无订阅的新建个人目录不应被关注捕获拒绝或创建投递");

        TeamSpace space = new TeamSpace();
        space.setId(spaceId);
        space.setTenantId(tenantId);
        space.setSpaceName("new-node-space");
        space.setOwnerId(actorId);
        space.setStatus(1);
        space.setDeleted(0);
        teamSpaceMapper.insert(space);
        TeamMember watcher = new TeamMember();
        watcher.setId(4151201L);
        watcher.setTenantId(tenantId);
        watcher.setSpaceId(spaceId);
        watcher.setUserId(watcherId);
        watcher.setRole(2);
        watcher.setDeleted(0);
        teamMemberMapper.insert(watcher);
        FileNode teamFolder = insertNode(tenantId, actorId, spaceId, "new-team", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, watcherId, teamFolder.getId(), null);
        FileNode teamMemoryNode = copyNode(teamFolder);
        teamMemoryNode.setTenantId(null);
        publishCommitted(publisher, teamMemoryNode, SyncChangeEvent.ChangeType.CREATE);

        FileWatchCaptureEvent teamCapture = captures.get(1);
        List<FileWatchDelivery> deliveries = deliveries(tenantId, teamCapture.getEventId());
        assertEquals(tenantId, teamMemoryNode.getTenantId());
        assertEquals(1, deliveries.size(), "团队新建目录应为有效订阅写入一条投递");
        assertEquals(watcherId, deliveries.get(0).getUserId());
        assertEquals(teamFolder.getId(), deliveries.get(0).getNodeId());

        setTenant(otherTenantId);
        FileNode foreignNode = insertNode(otherTenantId, actorId, null, "foreign", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        setUpUser(actorId, tenantId);
        assertThrows(IllegalStateException.class,
                () -> publishCommitted(publisher, foreignNode, SyncChangeEvent.ChangeType.CREATE));
        assertEquals(0, deliveries(otherTenantId, teamCapture.getEventId()).size(),
                "跨租户节点不得复用当前租户的关注事件");
    }

    @Test
    void overlappingSubscriptions_andDuplicateCapture_produceOneDelivery() throws Exception {
        long tenantId = 4201L;
        long ownerId = 42011L;
        long actorId = 42012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "overlap-owner");
        insertUser(actorId, tenantId, "overlap-actor");

        FileNode root = insertNode(tenantId, ownerId, null, "root", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        FileNode folder = insertNode(tenantId, ownerId, null, "folder", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), root.getId(), root.getPath());
        FileNode file = insertNode(tenantId, ownerId, null, "report.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), folder.getId(), folder.getPath());
        FileWatch rootWatch = insertWatch(tenantId, ownerId, root.getId(), null);
        FileWatch folderWatch = insertWatch(tenantId, ownerId, folder.getId(), null);
        FileWatch fileWatch = insertWatch(tenantId, ownerId, file.getId(), null);

        FileWatchCaptureEvent event = event(file, 4201001L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(event);
        // 事件重放走同一真实唯一键，监听器应吞掉 DuplicateKeyException 并保留原投递。
        captureCommitted(event);

        List<FileWatchDelivery> deliveries = deliveries(tenantId, event.getEventId());
        assertEquals(1, deliveries.size());
        JsonNode watchIds = objectMapper.readTree(deliveries.get(0).getWatchIds());
        assertEquals(3, watchIds.size());
        assertEquals(Set.of(rootWatch.getId(), folderWatch.getId(), fileWatch.getId()),
                Set.of(Long.parseLong(watchIds.get(0).asText()), Long.parseLong(watchIds.get(1).asText()),
                        Long.parseLong(watchIds.get(2).asText())));

        setTenant(tenantId);
        assertTrue(deliveryProcessor.processOne(tenantId, deliveries.get(0).getId()));
        Notification notification = notificationMapper.findByTenantUserEvent(tenantId, ownerId,
                event.getEventId());
        assertNotNull(notification);
        assertEquals(FileWatchDeliveryProcessor.STATUS_SENT,
                deliveryMapper.selectById(deliveries.get(0).getId()).getStatus());
    }

    @Test
    void cancelledWatch_thenResubscribe_doesNotConsumeOldDeliveryGeneration() throws Exception {
        long tenantId = 4301L;
        long ownerId = 43011L;
        long actorId = 43012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "generation-owner");
        insertUser(actorId, tenantId, "generation-actor");
        FileNode node = insertNode(tenantId, ownerId, null, "generation.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        FileWatch oldWatch = insertWatch(tenantId, ownerId, node.getId(), 4301001L);
        FileWatchCaptureEvent event = event(node, 4301002L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(event);

        setTenant(tenantId);
        fileWatchMapper.delete(new LambdaQueryWrapper<FileWatch>()
                .eq(FileWatch::getTenantId, tenantId)
                .eq(FileWatch::getUserId, ownerId)
                .eq(FileWatch::getNodeId, node.getId()));
        FileWatch newWatch = insertWatch(tenantId, ownerId, node.getId(), 4301003L);

        FileWatchDelivery delivery = deliveries(tenantId, event.getEventId()).get(0);
        assertEquals(oldWatch.getId(),
                Long.parseLong(objectMapper.readTree(delivery.getWatchIds()).get(0).asText()));
        assertTrue(deliveryProcessor.processOne(tenantId, delivery.getId()));
        FileWatchDelivery suppressed = deliveryMapper.selectById(delivery.getId());
        assertEquals(FileWatchDeliveryProcessor.STATUS_SUPPRESSED, suppressed.getStatus());
        assertNull(notificationMapper.findByTenantUserEvent(tenantId, ownerId, event.getEventId()));
        assertNotNull(newWatch);
    }

    @Test
    void actorSelf_isSkipped_crossTenantWatch_isIgnored_andRevokedOwnerIsSuppressed() {
        long tenantId = 4401L;
        long otherTenantId = 4402L;
        long ownerId = 44011L;
        long otherOwnerId = 44021L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "boundary-owner");
        insertUser(44013L, tenantId, "boundary-actor");
        insertUser(44014L, tenantId, "boundary-new-owner");
        FileNode node = insertNode(tenantId, ownerId, null, "boundary.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, ownerId, node.getId(), 4401001L);

        // 无需伪造跨租户节点：file_watch 没有外键，故意插入同 nodeId 的异租户记录，
        // 验证 JOIN 的 tenant_id 与事件 tenant_id 约束同时生效。
        setTenant(otherTenantId);
        insertUser(otherOwnerId, otherTenantId, "other-tenant-owner");
        insertWatch(otherTenantId, otherOwnerId, node.getId(), 4402001L);

        setTenant(tenantId);
        FileWatchCaptureEvent selfEvent = event(node, 4401002L, SyncChangeEvent.ChangeType.UPDATE,
                ownerId, null);
        captureCommitted(selfEvent);
        assertEquals(0, deliveries(tenantId, selfEvent.getEventId()).size());
        assertEquals(0, deliveries(otherTenantId, selfEvent.getEventId()).size());

        FileWatchCaptureEvent otherActorEvent = event(node, 4401003L, SyncChangeEvent.ChangeType.UPDATE,
                44013L, null);
        captureCommitted(otherActorEvent);
        List<FileWatchDelivery> deliveries = deliveries(tenantId, otherActorEvent.getEventId());
        assertEquals(1, deliveries.size());
        assertEquals(0, deliveries(otherTenantId, otherActorEvent.getEventId()).size());

        // 上面的异租户查询会切换 ThreadLocal；投递前恢复事件租户，确保真实 Mapper 更新和处理使用同一租户上下文。
        setTenant(tenantId);
        int ownerUpdateRows = fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()
                .eq(FileNode::getTenantId, tenantId)
                .eq(FileNode::getId, node.getId())
                .set(FileNode::getOwnerId, 44014L));
        assertEquals(1, ownerUpdateRows);
        assertEquals(44014L, fileNodeMapper.selectById(node.getId()).getOwnerId());
        assertTrue(deliveryProcessor.processOne(tenantId, deliveries.get(0).getId()));
        assertEquals(FileWatchDeliveryProcessor.STATUS_SUPPRESSED,
                deliveryMapper.selectById(deliveries.get(0).getId()).getStatus());
        assertNull(notificationMapper.findByTenantUserEvent(tenantId, ownerId, otherActorEvent.getEventId()));
    }

    @Test
    void deletedDirectory_matchesParentAndDescendantWatches_oncePerUser() throws Exception {
        long tenantId = 4501L;
        long ownerId = 45011L;
        long actorId = 45012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "delete-owner");
        insertUser(actorId, tenantId, "delete-actor");
        FileNode root = insertNode(tenantId, ownerId, null, "deleted-root", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        FileNode child = insertNode(tenantId, ownerId, null, "child", NodeType.FOLDER.getCode(),
                NodeStatus.NORMAL.getCode(), root.getId(), root.getPath());
        FileNode leaf = insertNode(tenantId, ownerId, null, "leaf.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), child.getId(), child.getPath());
        insertWatch(tenantId, ownerId, root.getId(), null);
        insertWatch(tenantId, ownerId, child.getId(), null);
        insertWatch(tenantId, ownerId, leaf.getId(), null);

        fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()
                .eq(FileNode::getTenantId, tenantId)
                .eq(FileNode::getId, root.getId())
                .set(FileNode::getStatus, NodeStatus.RECYCLED.getCode()));
        FileNode recycledRoot = fileNodeMapper.selectById(root.getId());
        FileWatchCaptureEvent event = event(recycledRoot, 4501001L, SyncChangeEvent.ChangeType.DELETE,
                actorId, null);
        captureCommitted(event);

        List<FileWatchDelivery> deliveries = deliveries(tenantId, event.getEventId());
        assertEquals(1, deliveries.size());
        assertEquals(3, objectMapper.readTree(deliveries.get(0).getWatchIds()).size());
        assertTrue(deliveryProcessor.processOne(tenantId, deliveries.get(0).getId()));

        Notification notification = notificationMapper.findByTenantUserEvent(tenantId, ownerId,
                event.getEventId());
        assertNotNull(notification);
        assertNull(notification.getContent());
        assertEquals(FileWatchDeliveryProcessor.STATUS_SENT,
                deliveryMapper.selectById(deliveries.get(0).getId()).getStatus());
    }

    @Test
    void notificationInsertFailure_rollsBackProcessing_thenRetryCompletes() {
        long tenantId = 4601L;
        long ownerId = 46011L;
        long actorId = 46012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "retry-owner");
        insertUser(actorId, tenantId, "retry-actor");
        FileNode node = insertNode(tenantId, ownerId, null, "retry.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, ownerId, node.getId(), null);
        FileWatchCaptureEvent event = event(node, 4601001L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(event);
        FileWatchDelivery delivery = deliveries(tenantId, event.getEventId()).get(0);

        NotificationMapper failingNotificationMapper = mock(NotificationMapper.class);
        when(failingNotificationMapper.findByTenantUserEvent(tenantId, ownerId, event.getEventId()))
                .thenReturn(null);
        doThrow(new IllegalStateException("notification database unavailable"))
                .when(failingNotificationMapper).insert(any(Notification.class));
        FileWatchDeliveryProcessor failingProcessor = new FileWatchDeliveryProcessor(
                deliveryMapper, fileWatchMapper, failingNotificationMapper, accessService, objectMapper,
                eventPublisher);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.executeWithoutResult(status -> failingProcessor.processOne(tenantId, delivery.getId()));
        });
        setTenant(tenantId);
        FileWatchDelivery afterRollback = deliveryMapper.selectById(delivery.getId());
        assertEquals(FileWatchDeliveryProcessor.STATUS_PENDING, afterRollback.getStatus());
        assertEquals(0, afterRollback.getRetryCount());
        assertNull(notificationMapper.findByTenantUserEvent(tenantId, ownerId, event.getEventId()));

        retryService.recordFailure(tenantId, delivery.getId(), failure);
        FileWatchDelivery retry = deliveryMapper.selectById(delivery.getId());
        assertEquals(FileWatchDeliveryRetryService.STATUS_RETRY, retry.getStatus());
        assertEquals(1, retry.getRetryCount());
        deliveryMapper.update(null, new LambdaUpdateWrapper<FileWatchDelivery>()
                .eq(FileWatchDelivery::getTenantId, tenantId)
                .eq(FileWatchDelivery::getId, delivery.getId())
                .set(FileWatchDelivery::getNextRetryAt, LocalDateTime.now().minusSeconds(1)));

        assertTrue(deliveryProcessor.processOne(tenantId, delivery.getId()));
        assertNotNull(notificationMapper.findByTenantUserEvent(tenantId, ownerId, event.getEventId()));
        assertEquals(FileWatchDeliveryProcessor.STATUS_SENT,
                deliveryMapper.selectById(delivery.getId()).getStatus());
    }

    @Test
    void twoConsumers_raceOnOneDelivery_andInsertOneNotification() throws Exception {
        long tenantId = 4701L;
        long ownerId = 47011L;
        long actorId = 47012L;
        setTenant(tenantId);
        insertUser(ownerId, tenantId, "concurrent-owner");
        insertUser(actorId, tenantId, "concurrent-actor");
        FileNode node = insertNode(tenantId, ownerId, null, "concurrent.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, ownerId, node.getId(), null);
        FileWatchCaptureEvent event = event(node, 4701001L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(event);
        FileWatchDelivery delivery = deliveries(tenantId, event.getEventId()).get(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> consumer = () -> {
                setTenant(tenantId);
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return deliveryProcessor.processOne(tenantId, delivery.getId());
                } finally {
                    TenantContext.clear();
                }
            };
            Future<Boolean> first = executor.submit(consumer);
            Future<Boolean> second = executor.submit(consumer);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        setTenant(tenantId);
        assertEquals(1, notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, ownerId)
                .eq(Notification::getEventId, event.getEventId())));
        assertEquals(FileWatchDeliveryProcessor.STATUS_SENT,
                deliveryMapper.selectById(delivery.getId()).getStatus());
    }

    @Test
    void teamMemberPermission_isRecheckedAtDeliveryTime() {
        long tenantId = 4801L;
        long spaceId = 480101L;
        long memberId = 48011L;
        long actorId = 48012L;
        setTenant(tenantId);
        insertUser(memberId, tenantId, "team-member");
        insertUser(actorId, tenantId, "team-actor");
        TeamSpace space = new TeamSpace();
        space.setId(spaceId);
        space.setTenantId(tenantId);
        space.setSpaceName("reliability-space");
        space.setOwnerId(actorId);
        space.setStatus(1);
        space.setDeleted(0);
        teamSpaceMapper.insert(space);
        TeamMember member = new TeamMember();
        member.setId(4801101L);
        member.setTenantId(tenantId);
        member.setSpaceId(spaceId);
        member.setUserId(memberId);
        member.setRole(2);
        member.setDeleted(0);
        teamMemberMapper.insert(member);
        FileNode node = insertNode(tenantId, actorId, spaceId, "team.txt", NodeType.FILE.getCode(),
                NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, memberId, node.getId(), null);

        FileWatchCaptureEvent event = event(node, 4801001L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(event);
        FileWatchDelivery first = deliveries(tenantId, event.getEventId()).get(0);
        assertTrue(deliveryProcessor.processOne(tenantId, first.getId()));
        Notification firstNotification = notificationMapper.findByTenantUserEvent(tenantId, memberId,
                event.getEventId());
        assertNotNull(firstNotification);

        setTenant(tenantId);
        // 使用真实逻辑删除模拟撤权；直接 updateById 可能被逻辑删除元数据排除，无法证明成员已失效。
        assertEquals(1, teamMemberMapper.deleteById(member.getId()));
        assertNull(teamMemberMapper.selectById(member.getId()));
        FileWatchCaptureEvent revokedEvent = event(node, 4801002L, SyncChangeEvent.ChangeType.UPDATE,
                actorId, null);
        captureCommitted(revokedEvent);
        FileWatchDelivery revokedDelivery = deliveries(tenantId, revokedEvent.getEventId()).get(0);
        assertTrue(deliveryProcessor.processOne(tenantId, revokedDelivery.getId()));
        assertEquals(FileWatchDeliveryProcessor.STATUS_SUPPRESSED,
                deliveryMapper.selectById(revokedDelivery.getId()).getStatus());
        assertNull(notificationMapper.findByTenantUserEvent(tenantId, memberId, revokedEvent.getEventId()));

        // 已送达的历史通知也必须在读取时重新核权，撤权后不能返回旧的可定位字段。
        var unavailable = notificationService.toVO(firstNotification, tenantId, memberId);
        assertFalse(unavailable.getAvailable());
        assertEquals("关注内容已不可用", unavailable.getTitle());
        assertNull(unavailable.getContent());
        assertNull(unavailable.getRefId());
        assertNull(unavailable.getNodeId());
        assertNull(unavailable.getParentId());
        assertNull(unavailable.getSpaceId());
        assertFalse(notificationService.target(tenantId, memberId, firstNotification.getId()).isAvailable());
    }

    @Test
    void anotherMemberCreatesChild_inWatchedTeamFolder_createsUnreadNotification() throws Exception {
        long tenantId = 4951L;
        long spaceId = 495101L;
        long watcherId = 49511L;
        long actorId = 49512L;
        setUpUser(actorId, tenantId);
        insertUser(watcherId, tenantId, "folder-watcher");
        insertUser(actorId, tenantId, "folder-actor");

        TeamSpace space = new TeamSpace();
        space.setId(spaceId);
        space.setTenantId(tenantId);
        space.setSpaceName("watched-folder-space");
        space.setOwnerId(actorId);
        space.setStatus(1);
        space.setDeleted(0);
        teamSpaceMapper.insert(space);
        TeamMember member = new TeamMember();
        member.setId(4951101L);
        member.setTenantId(tenantId);
        member.setSpaceId(spaceId);
        member.setUserId(watcherId);
        member.setRole(2);
        member.setDeleted(0);
        teamMemberMapper.insert(member);

        FileNode folder = insertNode(tenantId, actorId, spaceId, "watched-folder",
                NodeType.FOLDER.getCode(), NodeStatus.NORMAL.getCode(), 0L, null);
        insertWatch(tenantId, watcherId, folder.getId(), null);
        FileNode child = insertNode(tenantId, actorId, spaceId, "created.txt",
                NodeType.FILE.getCode(), NodeStatus.NORMAL.getCode(), folder.getId(), folder.getPath());

        List<FileWatchCaptureEvent> captures = new ArrayList<>();
        publishCommitted(capturePublisher(captures), child, SyncChangeEvent.ChangeType.CREATE);
        assertEquals(1, captures.size());
        List<FileWatchDelivery> deliveries = deliveries(tenantId, captures.get(0).getEventId());
        assertEquals(1, deliveries.size());
        assertEquals(watcherId, deliveries.get(0).getUserId());
        assertTrue(objectMapper.readTree(deliveries.get(0).getWatchIds()).get(0).isTextual(),
                "生产环境会把雪花 Long ID 序列化为字符串");

        assertTrue(deliveryProcessor.processOne(tenantId, deliveries.get(0).getId()));
        Notification notification = notificationMapper.findByTenantUserEvent(
                tenantId, watcherId, captures.get(0).getEventId());
        assertNotNull(notification);
        assertEquals(0, notification.getRead());
        assertEquals(1L, notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, watcherId)
                .eq(Notification::getRead, 0)));
    }

    @Test
    void notificationTarget_rejectsAnotherUsersNotificationId() {
        long tenantId = 4901L;
        long notificationOwnerId = 49011L;
        long otherUserId = 49012L;
        setTenant(tenantId);
        insertUser(notificationOwnerId, tenantId, "notification-owner");
        insertUser(otherUserId, tenantId, "notification-other");
        Notification notification = new Notification();
        notification.setTenantId(tenantId);
        notification.setUserId(notificationOwnerId);
        notification.setType("FILE_CHANGE");
        notification.setTitle("历史标题");
        notification.setRefType("file");
        notification.setRefId(490101L);
        notification.setEventId(4901001L);
        notification.setNodeId(490101L);
        notification.setSpaceId(490102L);
        notification.setChangeType("UPDATE");
        notification.setRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);

        assertThrows(BusinessException.class,
                () -> notificationService.target(tenantId, otherUserId, notification.getId()));
    }

    private void captureCommitted(FileWatchCaptureEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> captureListener.capture(event));
    }

    private ReliableEventPublisher capturePublisher(List<FileWatchCaptureEvent> captures) {
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof FileWatchCaptureEvent capture) {
                captures.add(capture);
                captureListener.capture(capture);
            }
        };
        return new ReliableEventPublisher(eventLogMapper, publisher, objectMapper);
    }

    private void publishCommitted(ReliableEventPublisher publisher, FileNode node,
                                  SyncChangeEvent.ChangeType changeType) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> publisher.publishSyncChange(node, changeType));
    }

    private FileWatchCaptureEvent event(FileNode node, long eventId, SyncChangeEvent.ChangeType changeType,
                                       Long actorId, Long oldParentId) {
        return new FileWatchCaptureEvent(this, eventId, node, changeType, oldParentId,
                actorId, LocalDateTime.now().minusSeconds(1));
    }

    private FileNode insertNode(long tenantId, long ownerId, Long spaceId, String name,
                                int nodeType, int status, long parentId, String parentPath) {
        setTenant(tenantId);
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setNodeType(nodeType);
        node.setName(name);
        node.setPath(parentPath == null ? "/" + name : parentPath + "/" + name);
        node.setFileSize(nodeType == NodeType.FILE.getCode() ? 1024L : 0L);
        node.setContentType(nodeType == NodeType.FILE.getCode() ? "text/plain" : null);
        node.setSuffix(nodeType == NodeType.FILE.getCode() ? "txt" : null);
        node.setStatus(status);
        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
        node.setUploaderId(ownerId);
        node.setOwnerId(ownerId);
        node.setSpaceId(spaceId);
        node.setRefCount(1);
        node.setVersion(0);
        node.setHidden(0);
        node.setDeleted(0);
        fileNodeMapper.insert(node);
        return node;
    }

    private FileNode copyNode(FileNode source) {
        FileNode copy = new FileNode();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setParentId(source.getParentId());
        copy.setNodeType(source.getNodeType());
        copy.setName(source.getName());
        copy.setPath(source.getPath());
        copy.setFileSize(source.getFileSize());
        copy.setFileMd5(source.getFileMd5());
        copy.setContentType(source.getContentType());
        copy.setSuffix(source.getSuffix());
        copy.setStoragePath(source.getStoragePath());
        copy.setObjectId(source.getObjectId());
        copy.setStatus(source.getStatus());
        copy.setUploadStatus(source.getUploadStatus());
        copy.setUploaderId(source.getUploaderId());
        copy.setOwnerId(source.getOwnerId());
        copy.setSpaceId(source.getSpaceId());
        copy.setRefCount(source.getRefCount());
        copy.setVersion(source.getVersion());
        copy.setThumbnailPath(source.getThumbnailPath());
        copy.setHidden(source.getHidden());
        copy.setDeleted(source.getDeleted());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private FileWatch insertWatch(long tenantId, long userId, long nodeId, Long id) {
        setTenant(tenantId);
        FileWatch watch = new FileWatch();
        watch.setId(id);
        watch.setTenantId(tenantId);
        watch.setUserId(userId);
        watch.setNodeId(nodeId);
        watch.setCreatedAt(LocalDateTime.now().minusSeconds(1));
        fileWatchMapper.insert(watch);
        return watch;
    }

    private List<FileWatchDelivery> deliveries(long tenantId, long eventId) {
        setTenant(tenantId);
        return deliveryMapper.selectList(new LambdaQueryWrapper<FileWatchDelivery>()
                .eq(FileWatchDelivery::getTenantId, tenantId)
                .eq(FileWatchDelivery::getEventId, eventId)
                .orderByAsc(FileWatchDelivery::getId));
    }

    private long deliveryCount(long tenantId) {
        return deliveryMapper.selectCount(new LambdaQueryWrapper<FileWatchDelivery>()
                .eq(FileWatchDelivery::getTenantId, tenantId));
    }

    private void setTenant(long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setTenantMode("SAAS");
    }
}
