package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.PhysicalDeleteEventListener;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 回收站永久删除事务边界集成测试（事务边界治理 F4）。
 * <p>
 * 独立于 AbstractIntegrationTest（类级不开启测试事务，真实提交）：
 * 用 TransactionTemplate 显式圈定事务边界，断言
 * 1) 事务内不再执行任何 S3 物理删除（引用归零仅写 event_log outbox）；
 * 2) 提交后由本地 AFTER_COMMIT 兜底监听器删除 S3 并标记对象失效；
 * 3) 引用未归零（remaining &gt; 0）时不发布 PHYSICAL_DELETE 事件。
 * 测试环境未配置 rocketmq.name-server，走「本地兜底」通道。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(RecycleBinPhysicalDeleteIntegrationTest.RbDeleteConfig.class)
class RecycleBinPhysicalDeleteIntegrationTest {

    @TestConfiguration
    static class RbDeleteConfig {

        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher(EventLogMapper eventLogMapper,
                                                      ApplicationEventPublisher eventPublisher,
                                                      ObjectMapper objectMapper) {
            return new ReliableEventPublisher(eventLogMapper, eventPublisher, objectMapper);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            // Spy：真实 DB 逻辑 + 可断言 deletePhysical 调用发生在事务外
            return Mockito.spy(svc);
        }

        @Bean
        PhysicalDeleteEventListener physicalDeleteEventListener(FileObjectService fileObjectService,
                                                               StorageService storageService) {
            return new PhysicalDeleteEventListener(fileObjectService, storageService);
        }

        @Bean
        FileService fileService(FileNodeMapper fileNodeMapper, UserQuotaMapper userQuotaMapper,
                                com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper,
                                CloudStorageService cloudStorageService,
                                ReliableEventPublisher reliableEventPublisher,
                                FileObjectService fileObjectService) {
            FileServiceImpl svc = new FileServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "cloudStorageService", cloudStorageService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            return svc;
        }

        @Bean
        RecycleBinService recycleBinService(FileNodeMapper fileNodeMapper, UserQuotaMapper userQuotaMapper,
                                            com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper,
                                            FileService fileService, FileObjectService fileObjectService,
                                            ReliableEventPublisher reliableEventPublisher) {
            RecycleBinServiceImpl svc = new RecycleBinServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "fileService", fileService);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            return svc;
        }
    }

    private static final Long USER = 3001L;
    private static final Long TENANT = 1L;

    @Autowired
    private RecycleBinService recycleBinService;
    @Autowired
    private FileObjectService fileObjectService;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private EventLogMapper eventLogMapper;
    @Autowired
    private StorageService storageService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        setUser(USER, TENANT);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (3001, 1, 'rb-tx-test', 'x', 1, 0, NULL, 0)");
        // 清空上一用例的调用记录：Spy 保持真实逻辑，仅清除 verify 计数
        Mockito.clearInvocations(storageService, fileObjectService);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM event_log WHERE event_type = 'PHYSICAL_DELETE'");
        jdbcTemplate.update("DELETE FROM file_node WHERE owner_id = " + USER);
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = " + TENANT + " AND md5 LIKE 'md5-rb-%'");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = " + USER);
        UserContext.clear();
        TenantContext.clear();
    }

    private void setUser(Long userId, Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .username("rb-test-" + userId)
                .build());
    }

    private FileObject insertObject(String md5, int refCount) {
        FileObject obj = new FileObject();
        obj.setTenantId(TENANT);
        obj.setMd5(md5);
        obj.setSize(1024L);
        obj.setStoragePath("t1/" + md5);
        obj.setRefCount(refCount);
        obj.setStatus(0);
        fileObjectMapper.insert(obj);
        return obj;
    }

    private FileNode insertRecycledFile(String name, Long objectId, String md5, String storagePath) {
        FileNode node = new FileNode();
        node.setTenantId(TENANT);
        node.setParentId(0L);
        node.setNodeType(NodeType.FILE.getCode());
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setFileMd5(md5);
        node.setContentType("text/plain");
        node.setSuffix("txt");
        node.setStoragePath(storagePath);
        node.setObjectId(objectId);
        node.setStatus(NodeStatus.RECYCLED.getCode());
        node.setUploadStatus(2);
        node.setOwnerId(USER);
        node.setUploaderId(USER);
        node.setRefCount(1);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }

    private long countPhysicalDeleteEvents() {
        return eventLogMapper.selectCount(new LambdaQueryWrapper<EventLog>()
                .eq(EventLog::getEventType, "PHYSICAL_DELETE"));
    }

    private EventMessage lastPhysicalDeleteMessage() throws Exception {
        List<EventLog> logs = eventLogMapper.selectList(new LambdaQueryWrapper<EventLog>()
                .eq(EventLog::getEventType, "PHYSICAL_DELETE")
                .orderByDesc(EventLog::getId));
        assertFalse(logs.isEmpty(), "应存在 PHYSICAL_DELETE 事件");
        return objectMapper.readValue(logs.get(0).getPayload(), EventMessage.class);
    }

    @Test
    @DisplayName("引用归零才发布事件：事务内无 S3 调用，提交后异步删除并标记对象失效")
    void permanentDelete_refCountReachesZero_publishesEventAndDeletesS3AfterCommit() throws Exception {
        String md5 = "md5-rb-zero-" + System.nanoTime();
        FileObject obj = insertObject(md5, 2);
        FileNode nodeA = insertRecycledFile("rb-a.txt", obj.getId(), md5, obj.getStoragePath());
        FileNode nodeB = insertRecycledFile("rb-b.txt", obj.getId(), md5, obj.getStoragePath());

        // 第一节点删除：引用 2->1，未归零 -> 不发布事件、事务内不删除 S3
        transactionTemplate.executeWithoutResult(status -> {
            recycleBinService.permanentDelete(List.of(nodeA.getId()));
            assertEquals(0L, countPhysicalDeleteEvents(), "引用未归零不应发布 PHYSICAL_DELETE 事件");
            verify(storageService, never()).deleteObject(anyString());
            verify(fileObjectService, never()).deletePhysical(anyLong());
        });
        assertEquals(1, fileObjectMapper.selectById(obj.getId()).getRefCount(), "引用计数应减为 1");
        assertEquals(0L, countPhysicalDeleteEvents(), "提交后仍不应产生物理删除事件");

        // 第二节点删除：引用 1->0，归零 -> 事务内只写事件，提交后异步删除 S3 并标记对象失效
        transactionTemplate.executeWithoutResult(status -> {
            recycleBinService.permanentDelete(List.of(nodeB.getId()));
            assertEquals(1L, countPhysicalDeleteEvents(), "引用归零应发布 PHYSICAL_DELETE 事件");
            verify(storageService, never()).deleteObject(anyString());
            verify(fileObjectService, never()).deletePhysical(anyLong());
        });

        // 提交后本地 AFTER_COMMIT 兜底执行：deletePhysical（S3 删除 + 标记失效）
        verify(fileObjectService).deletePhysical(obj.getId());
        verify(storageService).deleteObject("t1/" + md5);
        assertNull(fileObjectMapper.selectById(obj.getId()), "物理删除后对象应被逻辑删除（查询不可见）");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, deleted FROM file_object WHERE id = " + obj.getId());
        assertEquals(1, ((Number) row.get("status")).intValue(), "对象状态应标记失效");
        assertEquals(1, ((Number) row.get("deleted")).intValue(), "对象应逻辑删除");

        // event_log payload 含 storagePath / md5 / tenantId
        EventMessage message = lastPhysicalDeleteMessage();
        assertEquals("PHYSICAL_DELETE", message.getEventType());
        assertNotNull(message.getEventLogId(), "事件应携带 eventLogId 作为幂等键");
        assertEquals("t1/" + md5, message.getFileNode().getStoragePath());
        assertEquals(md5, message.getFileNode().getFileMd5());
        assertEquals(TENANT, message.getFileNode().getTenantId());
    }

    @Test
    @DisplayName("旧数据无 objectId：按 storagePath 判重归零发布事件，提交后删除对应 S3 对象")
    void permanentDelete_legacyNodeWithoutObjectId_publishesEventAndDeletesByStoragePath() throws Exception {
        String md5 = "md5-rb-legacy-" + System.nanoTime();
        String storagePath = "t1/legacy/" + System.nanoTime();
        FileNode legacy = insertRecycledFile("rb-legacy.txt", null, md5, storagePath);

        transactionTemplate.executeWithoutResult(status -> {
            recycleBinService.permanentDelete(List.of(legacy.getId()));
            assertEquals(1L, countPhysicalDeleteEvents(), "旧数据按 storage_path 判重归零应发布 PHYSICAL_DELETE 事件");
            verify(storageService, never()).deleteObject(anyString());
            verify(fileObjectService, never()).deletePhysical(anyLong());
        });

        verify(storageService).deleteObject(storagePath);
        verify(fileObjectService, never()).deletePhysical(anyLong());

        EventMessage message = lastPhysicalDeleteMessage();
        assertEquals(storagePath, message.getFileNode().getStoragePath());
        assertNull(message.getFileNode().getObjectId(), "旧数据节点不携带 objectId");
    }
}
