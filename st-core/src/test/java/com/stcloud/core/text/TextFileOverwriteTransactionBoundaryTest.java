package com.stcloud.core.text;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
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
import org.junit.jupiter.api.AfterEach;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 文本覆盖事务边界集成测试（事务边界治理 F5）。
 * <p>
 * 独立于 AbstractIntegrationTest（类级不开启测试事务）：真实提交/回滚，
 * 断言 overwriteContent 的 S3 上传发生在事务外、DB 落库收敛进独立事务方法、
 * 事务失败后按引用归零规则清理本次上传对象。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(TextFileOverwriteTransactionBoundaryTest.TextBoundaryConfig.class)
class TextFileOverwriteTransactionBoundaryTest {

    @TestConfiguration
    static class TextBoundaryConfig {

        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        FileService fileService() {
            return Mockito.mock(FileService.class);
        }

        @Bean
        VersionService versionService() {
            return Mockito.mock(VersionService.class);
        }

        @Bean
        ApplicationEventPublisher eventPublisher() {
            return Mockito.mock(ApplicationEventPublisher.class);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher() {
            return Mockito.mock(ReliableEventPublisher.class);
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
        TextFileService textFileService(FileNodeMapper fileNodeMapper,
                                        FileObjectService fileObjectService,
                                        StorageService storageService,
                                        CloudStorageService cloudStorageService,
                                        UserQuotaMapper userQuotaMapper,
                                        TeamStorageMapper teamStorageMapper,
                                        ReliableEventPublisher reliableEventPublisher) {
            return new TextFileServiceImpl(fileNodeMapper, fileObjectService, storageService,
                    cloudStorageService, userQuotaMapper, teamStorageMapper, reliableEventPublisher);
        }
    }

    @Autowired
    private TextFileService textFileService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private ReliableEventPublisher reliableEventPublisher;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private UserQuotaMapper userQuotaMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, 1L);
        Mockito.reset(storageService, reliableEventPublisher);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (1001, 1, 'tx-text', 'x', 1, 0, NULL, 0)");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM file_node WHERE name LIKE 'tx-text%'");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 1001");
        UserContext.clear();
        TenantContext.clear();
    }

    private void setUpUser(Long userId, Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .username("test-user-" + userId)
                .build());
    }

    /** 插入已完成文本文件节点（含旧对象记录），storage_used 预置 10 字节 */
    private FileNode insertTextNode(byte[] oldContent) {
        String oldMd5 = DigestUtil.md5Hex(new ByteArrayInputStream(oldContent));
        FileObject oldObj = new FileObject();
        oldObj.setTenantId(1L);
        oldObj.setMd5(oldMd5);
        oldObj.setSize((long) oldContent.length);
        oldObj.setStoragePath("1/" + oldMd5);
        oldObj.setRefCount(1);
        oldObj.setStatus(0);
        fileObjectMapper.insert(oldObj);

        FileNode node = new FileNode();
        node.setTenantId(1L);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName("tx-text.txt");
        node.setPath("/tx-text.txt");
        node.setFileSize((long) oldContent.length);
        node.setFileMd5(oldMd5);
        node.setContentType("text/plain");
        node.setSuffix("txt");
        node.setStoragePath(oldObj.getStoragePath());
        node.setObjectId(oldObj.getId());
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setOwnerId(USER_ID);
        node.setUploaderId(USER_ID);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }

    @Test
    void overwriteContent_s3UploadRunsOutsideTransaction_andCommitsDb() {
        byte[] oldContent = "old".getBytes(StandardCharsets.UTF_8);
        FileNode node = insertTextNode(oldContent);
        byte[] newContent = "hello world".getBytes(StandardCharsets.UTF_8);
        String newMd5 = DigestUtil.md5Hex(new ByteArrayInputStream(newContent));

        // S3 上传发生在事务外（F5 断言）
        AtomicBoolean txActiveDuringUpload = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringUpload.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());

        textFileService.overwriteContent(node.getId(), newContent);

        assertFalse(txActiveDuringUpload.get(), "文本覆盖的 S3 上传应在事务外执行");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用结束后不应残留活动事务");
        // DB 已提交：节点更新 + 新对象记录 + 差值配额 + 旧对象引用释放
        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(newContent.length, after.getFileSize());
        assertEquals(newMd5, after.getFileMd5());
        assertEquals("1/" + newMd5, after.getStoragePath());
        FileObject newObj = fileObjectMapper.selectByTenantAndMd5(1L, newMd5);
        assertNotNull(newObj, "新内容对象记录应已落库");
        assertEquals(1, newObj.getRefCount());
        assertEquals(8L, userQuotaMapper.getUserQuota(USER_ID).getUsed());
    }

    @Test
    void overwriteContent_dbFailure_cleansUploadedObject() {
        byte[] newContent = "cleanup case".getBytes(StandardCharsets.UTF_8);
        FileNode node = insertTextNode("old".getBytes(StandardCharsets.UTF_8));
        String newMd5 = DigestUtil.md5Hex(new ByteArrayInputStream(newContent));
        // DB 事务内事件写入失败 -> 整个落库事务回滚（模拟 DB 写失败）
        doThrow(new RuntimeException("db commit boom"))
                .when(reliableEventPublisher).publishFileIndex(any(FileNode.class),
                        any(FileIndexEvent.ActionType.class));

        assertThrows(RuntimeException.class,
                () -> textFileService.overwriteContent(node.getId(), newContent));

        // 本次上传的对象应被尽力清理（记录已回滚、无引用）
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadObject(keyCaptor.capture(), any(InputStream.class),
                eq((long) newContent.length), anyString());
        verify(storageService).deleteObject(keyCaptor.getValue());
        assertEquals(0L, fileObjectMapper.selectCount(new LambdaQueryWrapper<FileObject>()
                        .eq(FileObject::getTenantId, 1L).eq(FileObject::getMd5, newMd5)).longValue(),
                "DB 失败后新对象记录应随事务回滚");
        FileNode after = fileNodeMapper.selectById(node.getId());
        assertEquals(3L, after.getFileSize(), "DB 失败后节点内容不应变化");
    }
}
