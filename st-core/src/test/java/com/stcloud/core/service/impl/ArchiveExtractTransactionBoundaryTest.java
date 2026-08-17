package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线解压事务边界集成测试（事务边界治理 F5）。
 * <p>
 * 独立于 AbstractIntegrationTest（类级不开启测试事务）：真实提交/回滚，
 * 断言 extractArchive 的 ZIP 下载与逐条目 S3 上传发生在事务外、全部落库收敛进一个事务方法、
 * 事务失败后按引用归零规则清理本次上传对象。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(ArchiveExtractTransactionBoundaryTest.ArchiveTxBoundaryConfig.class)
class ArchiveExtractTransactionBoundaryTest {

    @TestConfiguration
    static class ArchiveTxBoundaryConfig {

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
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            // Spy：默认走真实 DB 逻辑，测试内可按需拦截记录事务状态/模拟失败
            return Mockito.spy(svc);
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
        ReliableEventPublisher reliableEventPublisher() {
            return Mockito.mock(ReliableEventPublisher.class);
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
        ArchiveService archiveService() {
            return new ArchiveServiceImpl();
        }
    }

    @Autowired
    private ArchiveService archiveService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private FileObjectService fileObjectService;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private UserQuotaMapper userQuotaMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, 1L);
        Mockito.reset(storageService, fileObjectService);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (100, 1, 'tx-archive', 'x', 1, 0, NULL, 0)");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM file_node WHERE name IN ('a.txt', 'b.txt', 'folder') "
                + "OR name LIKE 'tx-archive%'");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 100");
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

    /** 构造 ZIP 字节：条目格式 "a.txt:hello" 或 "dir/"（目录） */
    private byte[] buildZip(String... specs) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String spec : specs) {
                int idx = spec.indexOf(':');
                String name = idx >= 0 ? spec.substring(0, idx) : spec;
                String content = idx >= 0 ? spec.substring(idx + 1) : "";
                if (name.endsWith("/")) {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(content.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        }
        return bos.toByteArray();
    }

    private FileNode insertZipNode(String name) {
        FileNode node = new FileNode();
        node.setTenantId(1L);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setContentType("application/zip");
        node.setSuffix("zip");
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setUploaderId(USER_ID);
        node.setOwnerId(USER_ID);
        node.setStoragePath("files/1/test/" + name);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }

    @Test
    void extractArchive_s3UploadRunsOutsideTransaction_andCommitsInOneTx() throws Exception {
        FileNode zip = insertZipNode("tx-archive.zip");
        byte[] zipData = buildZip("a.txt:hello", "folder/b.txt:world");
        when(storageService.downloadObject(zip.getStoragePath()))
                .thenAnswer(inv -> new ByteArrayInputStream(zipData));

        // 逐条目 S3 上传发生在事务外（F5 断言）
        AtomicBoolean txActiveDuringUpload = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringUpload.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());

        int count = archiveService.extractArchive(zip.getId(), 0L);

        assertEquals(2, count);
        assertFalse(txActiveDuringUpload.get(), "解压的 S3 上传应在事务外执行");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用结束后不应残留活动事务");
        // DB 已提交：文件夹/文件节点 + 配额 + 引用校正
        FileNode a = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "a.txt").eq(FileNode::getNodeType, 1));
        assertNotNull(a, "解压文件 a.txt 应已落库");
        FileNode folder = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "folder").eq(FileNode::getNodeType, 0));
        assertNotNull(folder, "解压文件夹 folder 应已落库");
        FileNode b = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, folder.getId()).eq(FileNode::getName, "b.txt"));
        assertNotNull(b, "嵌套文件 b.txt 应已落库");
        assertEquals(10L, userQuotaMapper.getUserQuota(USER_ID).getUsed(), "配额应按解压内容扣减");
        FileObject objA = fileObjectMapper.selectByTenantAndMd5(1L,
                DigestUtil.md5Hex("hello".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(objA, "解压对象记录应已落库");
    }

    @Test
    void extractArchive_dbFailure_cleansUploadedObjects() throws Exception {
        FileNode zip = insertZipNode("tx-archive.zip");
        byte[] zipData = buildZip("a.txt:hello", "folder/b.txt:world");
        when(storageService.downloadObject(zip.getStoragePath()))
                .thenAnswer(inv -> new ByteArrayInputStream(zipData));
        // 事务内对象归属失败 -> 整个落库事务回滚（模拟 DB 写失败）
        doThrow(new RuntimeException("db commit boom"))
                .when(fileObjectService).acquireByPath(eq(1L), anyString(), anyLong(), anyString());

        assertThrows(RuntimeException.class, () -> archiveService.extractArchive(zip.getId(), 0L));

        // 两个条目均已上传 S3，事务回滚后按引用归零规则尽力删除
        verify(storageService, times(2))
                .uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(storageService, times(2)).deleteObject(anyString());
        assertEquals(0L, fileNodeMapper.selectCount(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getName, "a.txt")), "DB 失败后节点应随事务回滚");
        assertEquals(0L, userQuotaMapper.getUserQuota(USER_ID).getUsed(), "DB 失败后配额不应扣减");
    }
}
