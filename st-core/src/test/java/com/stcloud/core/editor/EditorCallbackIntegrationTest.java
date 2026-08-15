package com.stcloud.core.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.FileObjectServiceImpl;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OnlyOffice 保存回调集成测试（H2 + 真实 Mapper + 本地 HttpServer 提供回调内容）。
 * 覆盖 TC-07（自动保存覆盖不生成版本）、TC-08/15（关闭生成 source=1 版本）、TC-09（伪造签名拒绝）、
 * TC-10（重复回调幂等）、TC-13（事件发布）、TC-14（配额差值）、TC-20（编辑标记移除）。
 */
@Import(EditorCallbackIntegrationTest.EditorCallbackTestConfig.class)
class EditorCallbackIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "test-onlyoffice-secret-0123456789abcdef";
    private static HttpServer httpServer;
    private static byte[] callbackContent;

    @TestConfiguration
    static class EditorCallbackTestConfig {

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
    private EditorLockService editorLockService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetMocks() {
        // @TestConfiguration 的 mock bean 跨测试方法共享，需重置避免调用污染
        Mockito.reset(versionService, storageService);
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

    private FileNode insertDocx(Long tenantId, Long ownerId) {
        FileNode node = insertFileNode(tenantId, ownerId, "报告.docx", 0);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, storage_used, storage_quota) "
                + "VALUES (?, ?, 'u', 'x', 1024, 1048576) ON DUPLICATE KEY UPDATE storage_used=1024", ownerId, tenantId);
        return node;
    }

    @Test
    void invalidToken_rejected() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        String badToken = Jwts.builder()
                .claim("key", node.getId() + "_0")
                .signWith(Keys.hmacShaKeyFor("wrong-secret-wrong-secret-wrong-sec".getBytes(StandardCharsets.UTF_8)))
                .compact();
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, node.getId() + "_0", badToken);
        assertThrows(EditorCallbackRejectedException.class,
                () -> editorCallbackService.handleCallback(node.getId(), req));
    }

    @Test
    void missingToken_rejected() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, node.getId() + "_0", null);
        assertThrows(EditorCallbackRejectedException.class,
                () -> editorCallbackService.handleCallback(node.getId(), req));
    }

    @Test
    void autosave_overwritesWithoutVersion() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        String key = node.getId() + "_0";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, key, signToken(node.getId(), 2, callbackUrl(), key));

        editorCallbackService.handleCallback(node.getId(), req);

        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(callbackContent.length, after.getFileSize());
        assertNotNull(after.getFileMd5());
        // 自动保存不生成版本
        verify(versionService, never()).snapshotCurrentVersion(any(), any(Integer.class));
        verify(versionService, never()).snapshotCurrentVersion(any());
        // 事件已发布（Outbox 行写入）
        Long outbox = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_log WHERE event_type = 'FILE_INDEX'", Long.class);
        assertTrue(outbox != null && outbox > 0, "保存后应发布索引事件");
    }

    @Test
    void close_generatesEditorVersion_andClearsEditMark() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        editorLockService.markEditing(node.getId(), "1");
        assertTrue(editorLockService.isEditing(node.getId()));

        String key = node.getId() + "_1";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 6, key, signToken(node.getId(), 6, callbackUrl(), key));
        editorCallbackService.handleCallback(node.getId(), req);

        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(callbackContent.length, after.getFileSize());
        // 关闭保存：生成 source=1 版本 + 裁剪 + 移除编辑标记（P1/D1/P2）
        verify(versionService).snapshotCurrentVersion(any(FileNode.class), eq(1));
        verify(versionService).pruneEditorVersions(node.getId(), 20);
        assertFalse(editorLockService.isEditing(node.getId()), "关闭回调应移除编辑标记");
    }

    @Test
    void duplicateCallback_isSkipped() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        String key = node.getId() + "_0";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, key, signToken(node.getId(), 2, callbackUrl(), key));

        editorCallbackService.handleCallback(node.getId(), req);
        editorCallbackService.handleCallback(node.getId(), req);

        // 幂等：同一 key+status+url 只落盘一次
        verify(storageService, times(1)).uploadObject(anyString(), any(), eq((long) callbackContent.length), anyString());
    }

    @Test
    void keyMismatch_rejected() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        String tokenKey = node.getId() + "_0";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, "999:0", signToken(node.getId(), 2, callbackUrl(), tokenKey));
        assertThrows(EditorCallbackRejectedException.class,
                () -> editorCallbackService.handleCallback(node.getId(), req));
    }

    @Test
    void quotaDelta_appliedOnGrow() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        String key = node.getId() + "_0";
        com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                request(node.getId(), 2, key, signToken(node.getId(), 2, callbackUrl(), key));

        editorCallbackService.handleCallback(node.getId(), req);

        // 原 1024 字节 → 新内容长度，增量计入 storage_used（TC-14）
        Long used = jdbcTemplate.queryForObject(
                "SELECT storage_used FROM sys_user WHERE id = ?", Long.class, 1L);
        assertEquals((long) callbackContent.length, used);
    }

    @Test
    void sizeTooLarge_rejected() {
        FileNode node = insertDocx(1L, 1L);
        setUpUser(1L, 1L);
        // 覆盖默认配置：缩小上限到 10 字节，内容超限拒绝
        EditorProperties p = (EditorProperties) ReflectionTestUtils
                .getField(editorCallbackService, "editorProperties");
        long old = p.getMaxSaveSize();
        p.setMaxSaveSize(10);
        try {
            String key = node.getId() + "_0";
            com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest req =
                    request(node.getId(), 2, key, signToken(node.getId(), 2, callbackUrl(), key));
            assertThrows(EditorCallbackRejectedException.class,
                    () -> editorCallbackService.handleCallback(node.getId(), req));
        } finally {
            p.setMaxSaveSize(old);
        }
    }
}

