package com.stcloud.core.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.FileObjectServiceImpl;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * OnlyOffice 保存回调事务边界集成测试（事务边界治理 F5）。
 * <p>
 * 独立于 AbstractIntegrationTest（类级不开启测试事务）：真实提交/回滚，
 * 断言 handleCallback 的 URL 下载与 S3 上传发生在事务外、DB 更新收敛进独立事务方法、
 * 事务失败后按引用归零规则清理本次上传对象。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(EditorCallbackTransactionBoundaryTest.EditorTxBoundaryConfig.class)
class EditorCallbackTransactionBoundaryTest {

    private static final String SECRET = "test-onlyoffice-secret-0123456789abcdef";
    private static HttpServer httpServer;
    private static byte[] callbackContent;

    @TestConfiguration
    static class EditorTxBoundaryConfig {

        @Bean
        EditorProperties editorProperties() {
            EditorProperties p = new EditorProperties();
            p.setJwtSecret(SECRET);
            p.setMaxSaveSize(1024 * 1024);
            p.setEditorVersionLimit(20);
            p.setAllowedCallbackHosts(List.of("localhost", "127.0.0.1"));
            return p;
        }

        @Bean
        EditorLockService editorLockService() {
            return new EditorLockService(null, "memory");
        }

        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        VersionService versionService() {
            return Mockito.mock(VersionService.class);
        }

        @Bean
        FileService fileService() {
            return Mockito.mock(FileService.class);
        }

        @Bean
        UploadManager uploadManager() {
            return new UploadManager();
        }

        @Bean
        UploadChunkManager uploadChunkManager() {
            return new UploadChunkManager();
        }

        @Bean
        UploadEventPublisher uploadEventPublisher(ReliableEventPublisher reliableEventPublisher) {
            return new UploadEventPublisher(reliableEventPublisher);
        }

        @Bean
        UploadCommitManager uploadCommitManager() {
            return new UploadCommitManager();
        }

        @Bean
        UploadStorageManager uploadStorageManager() {
            return new UploadStorageManager();
        }

        @Bean
        RocketMQTemplate rocketMQTemplate() {
            return Mockito.mock(RocketMQTemplate.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher(EventLogMapper eventLogMapper,
                                                      ApplicationEventPublisher eventPublisher,
                                                      ObjectMapper objectMapper) {
            return Mockito.spy(new ReliableEventPublisher(eventLogMapper, eventPublisher, objectMapper));
        }

        @Bean
        EditorCallbackServiceImpl editorCallbackService(EditorProperties editorProperties,
                                                        EditorLockService editorLockService,
                                                        FileNodeMapper fileNodeMapper,
                                                        FileObjectService fileObjectService,
                                                        StorageService storageService,
                                                        CloudStorageService cloudStorageService,
                                                        UserQuotaMapper userQuotaMapper,
                                                        TeamStorageMapper teamStorageMapper,
                                                        VersionService versionService,
                                                        ReliableEventPublisher reliableEventPublisher) {
            return new EditorCallbackServiceImpl(editorProperties, editorLockService, fileNodeMapper,
                    fileObjectService, storageService, cloudStorageService, userQuotaMapper,
                    teamStorageMapper, versionService, reliableEventPublisher);
        }
    }

    @Autowired
    private EditorCallbackService editorCallbackService;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private StorageService storageService;
    @Autowired
    private VersionService versionService;
    @Autowired
    private ReliableEventPublisher reliableEventPublisher;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(storageService, versionService, reliableEventPublisher);
    }

    @BeforeAll
    static void startServer() throws Exception {
        callbackContent = "editor-saved-content-1234567890".getBytes(StandardCharsets.UTF_8);
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/doc", exchange -> {
            exchange.sendResponseHeaders(200, callbackContent.length);
            exchange.getResponseBody().write(callbackContent);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM file_node WHERE name = '报告.docx'");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1");
        jdbcTemplate.update("DELETE FROM event_log");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 1");
        UserContext.clear();
        TenantContext.clear();
    }

    private String callbackUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/doc";
    }

    private String signToken(Long nodeId, Integer status, String url, String key) {
        return Jwts.builder()
                .claims(Map.of("key", key, "status", status, "url", url, "type", 2))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest request(Long nodeId, int status,
                                                                          String key, String token) {
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                new com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest();
        req.setKey(key);
        req.setStatus(status);
        req.setUrl(callbackUrl());
        req.setToken(token);
        req.setUsers(List.of("1"));
        return req;
    }

    private FileNode insertDocx() {
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(1L).tenantId(1L).username("u").build());
        FileNode node = new FileNode();
        node.setTenantId(1L);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName("报告.docx");
        node.setPath("/报告.docx");
        node.setFileSize(1024L);
        node.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        node.setSuffix("docx");
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setOwnerId(1L);
        node.setUploaderId(1L);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, storage_used, storage_quota) "
                + "VALUES (1, 1, 'u', 'x', 1024, 1048576) "
                + "ON DUPLICATE KEY UPDATE storage_used = 1024");
        return node;
    }

    @Test
    void handleCallback_downloadAndUploadRunOutsideTransaction_dbCommitInsideTransaction() {
        FileNode node = insertDocx();
        String key = node.getId() + "_1";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 6, key, signToken(node.getId(), 6, callbackUrl(), key));

        // S3 上传发生在事务外（F5 断言）
        AtomicBoolean txActiveDuringUpload = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringUpload.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        // DB 写（关闭保存的版本快照）应在事务内（落库收敛进独立事务方法）
        AtomicBoolean txActiveDuringDbWrite = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveDuringDbWrite.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(versionService).snapshotCurrentVersion(any(FileNode.class), eq(1));

        editorCallbackService.handleCallback(node.getId(), req);

        assertFalse(txActiveDuringUpload.get(), "回调 S3 上传应在事务外执行");
        assertTrue(txActiveDuringDbWrite.get(), "回调 DB 更新应在事务内完成");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用结束后不应残留活动事务");
        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(callbackContent.length, after.getFileSize());
        verify(versionService).pruneEditorVersions(node.getId(), 20);
    }

    @Test
    void handleCallback_dbFailure_cleansUploadedObject() {
        FileNode node = insertDocx();
        String key = node.getId() + "_0";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, key, signToken(node.getId(), 2, callbackUrl(), key));
        // DB 事务内事件写入失败 -> 整个落库事务回滚（模拟 DB 写失败）
        doThrow(new RuntimeException("db commit boom"))
                .when(reliableEventPublisher).publishFileIndex(any(FileNode.class),
                        any(FileIndexEvent.ActionType.class));

        assertThrows(RuntimeException.class,
                () -> editorCallbackService.handleCallback(node.getId(), req));

        // 本次上传的对象应被尽力清理（记录已回滚、无引用）
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadObject(keyCaptor.capture(), any(InputStream.class),
                eq((long) callbackContent.length), anyString());
        verify(storageService).deleteObject(keyCaptor.getValue());
        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(1024L, after.getFileSize(), "DB 失败后节点内容不应变化");
    }
}
