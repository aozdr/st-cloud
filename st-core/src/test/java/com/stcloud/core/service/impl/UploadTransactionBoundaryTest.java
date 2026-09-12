package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.ratelimit.SpeedLimitResult;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.config.UploadRelayConfig;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadCheckRequest;
import com.stcloud.core.dto.UploadCheckResponse;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.dto.UploadInitResponse;
import com.stcloud.core.dto.UploadMergeRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.RelayBufferManager;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadInitCommitManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import org.h2.api.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 上传路径事务边界集成测试（事务边界治理 F1-3 / F2-1 / F2-2）。
 * <p>
 * 独立于 AbstractIntegrationTest（类级不开启测试事务）：真实提交/回滚，
 * 断言 S3 调用发生在事务外、秒传创建发生在事务内、非秒传路径不开启事务、
 * simpleUpload DB 失败后按引用归零规则清理孤儿对象。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(UploadTransactionBoundaryTest.TxBoundaryConfig.class)
class UploadTransactionBoundaryTest {

    @TestConfiguration
    static class TxBoundaryConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
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
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        ApplicationEventPublisher eventPublisher() {
            return Mockito.mock(ApplicationEventPublisher.class);
        }

        @Bean
        SpeedLimitService speedLimitService() {
            return Mockito.mock(SpeedLimitService.class);
        }

        @Bean
        UserTransferLimiter userTransferLimiter() {
            return new UserTransferLimiter();
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            // Spy：默认走真实 DB 逻辑，测试内可按需拦截记录事务状态
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
        UploadStorageManager uploadStorageManager() {
            return new UploadStorageManager();
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
        UploadInitCommitManager uploadInitCommitManager() {
            return new UploadInitCommitManager();
        }

        @Bean
        UploadRelayConfig uploadRelayConfig() {
            UploadRelayConfig config = new UploadRelayConfig();
            try {
                config.setTempDir(Files.createTempDirectory("stcloud-txb-test").toString());
            } catch (Exception e) {
                throw new IllegalStateException("创建中转测试临时目录失败", e);
            }
            return config;
        }

        @Bean
        RelayBufferManager relayBufferManager() {
            return new RelayBufferManager();
        }

        @Bean
        UploadService uploadService() {
            return new UploadServiceImpl();
        }
    }

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FileObjectService fileObjectService;

    @Autowired
    private FileObjectMapper fileObjectMapper;

    @Autowired
    private FileNodeMapper fileNodeMapper;

    @Autowired
    private StorageService storageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private SpeedLimitService speedLimitService;

    @Autowired
    private ReliableEventPublisher reliableEventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VersionService versionService;

    /** H2 在第二批首条分片上抛真实 SQL 异常；前 500 条已由 Mapper 写入同一事务。 */
    public static class FailOnSecondBatchChunk implements Trigger {
        static volatile String lastUploadId;

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            lastUploadId = (String) newRow[2];
            if (((Number) newRow[4]).intValue() == 501) {
                throw new SQLException("injected second chunk batch failure");
            }
        }
    }

    /** 记录最近一次 simpleUpload 的 md5，供清理本次提交/回滚数据 */
    private String lastMd5;

    @BeforeEach
    void setUp() {
        setUpUser(1001L, 1L);
        Mockito.reset(storageService, fileService, speedLimitService, reliableEventPublisher,
                fileObjectService, versionService);
        FailOnSecondBatchChunk.lastUploadId = null;
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (1001, 1, 'tb-user', 'x', 1, 0, NULL, 0)");
        when(storageService.initMultipartUpload(anyString())).thenReturn("s3-tb-id");
        when(storageService.listUploadedParts(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(fileService.validateAndGetParentPath(anyLong())).thenReturn("/");
        when(fileService.resolveNameConflict(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(fileService.guessContentType(anyString())).thenReturn("text/plain");
        when(fileService.extractSuffix(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(dot + 1) : null;
        });
        when(fileService.toVO(any(FileNode.class))).thenAnswer(inv -> {
            FileNode n = inv.getArgument(0);
            FileNodeVO vo = new FileNodeVO();
            vo.setId(n.getId());
            vo.setName(n.getName());
            return vo;
        });
        // 默认服务端不限速（direct 模式）
        when(speedLimitService.resolve()).thenReturn(new SpeedLimitResult(0, 0));
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_upload_init_chunk");
        jdbcTemplate.update("DELETE FROM file_version WHERE file_node_id IN (SELECT id FROM file_node WHERE name LIKE 'tb-%')");
        jdbcTemplate.update("DELETE FROM upload_session WHERE file_node_id IN (SELECT id FROM file_node WHERE name LIKE 'tb-%')");
        jdbcTemplate.update("DELETE FROM file_chunk WHERE file_node_id IN (SELECT id FROM file_node WHERE name LIKE 'tb-%')");
        jdbcTemplate.update("DELETE FROM file_node WHERE name LIKE 'tb-%'");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1 AND (md5 LIKE 'tb-%' OR md5 = ?)", lastMd5);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 1001");
        UserContext.clear();
        TenantContext.clear();
        lastMd5 = null;
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

    @Test
    void teamUploadNameResolution_usesExplicitSpaceScope() {
        UploadServiceImpl service = new UploadServiceImpl();
        ReflectionTestUtils.setField(service, "fileService", fileService);
        when(fileService.resolveTeamNameConflict(9L, 0L, "tb-team.txt"))
                .thenReturn("tb-team.txt");

        assertEquals("tb-team.txt", ReflectionTestUtils.invokeMethod(service,
                "resolveUploadName", 9L, 0L, "tb-team.txt"));
        verify(fileService).resolveTeamNameConflict(9L, 0L, "tb-team.txt");
        verify(fileService, never()).resolveNameConflict(anyLong(), anyString());
    }

    @Test
    void checkInstantUpload_nonHitPath_opensNoTransaction() {
        setUpUser(1001L, 1L);
        // 只读检查入口记录当时是否处于活动事务
        AtomicBoolean txActiveDuringRead = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringRead.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(fileObjectService).findByTenantAndMd5(eq(1L), anyString());

        UploadCheckRequest req = new UploadCheckRequest();
        req.setFileMd5("tb-nonhit-" + System.nanoTime());
        req.setFileSize(100L);
        req.setFileName("tb-nonhit.txt");
        req.setParentId(0L);

        UploadCheckResponse resp = uploadService.checkInstantUpload(req);
        assertFalse(resp.getInstant(), "未命中应返回非秒传");
        assertFalse(txActiveDuringRead.get(), "非秒传路径只读检查不应开启事务");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用结束后不应残留活动事务");
    }

    @Test
    void checkInstantUpload_hitPath_commitsInTransaction() {
        setUpUser(1001L, 1L);
        String md5 = "tb-hit-" + System.nanoTime();
        FileObject existing = new FileObject();
        existing.setId(900001L);
        existing.setTenantId(1L);
        existing.setMd5(md5);
        existing.setSize(100L);
        existing.setStoragePath("t1/" + md5);
        existing.setRefCount(1);
        existing.setStatus(0);
        doReturn(existing).when(fileObjectService).findByTenantAndMd5(1L, md5);
        AtomicBoolean txActiveInCommit = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveInCommit.set(TransactionSynchronizationManager.isActualTransactionActive());
            return existing;
        }).when(fileObjectService).acquireByPath(eq(1L), eq(md5), anyLong(), anyString());

        UploadCheckRequest req = new UploadCheckRequest();
        req.setFileMd5(md5);
        req.setFileSize(100L);
        req.setFileName("tb-hit.txt");
        req.setParentId(0L);

        UploadCheckResponse resp = uploadService.checkInstantUpload(req);
        assertTrue(resp.getInstant(), "命中应返回秒传成功");
        assertNotNull(resp.getFileId());
        assertTrue(txActiveInCommit.get(), "秒传创建应在独立事务内完成（引用+节点+配额+事件）");

        FileNode node = fileNodeMapper.selectById(resp.getFileId());
        assertNotNull(node, "秒传节点应已落库");
        assertEquals(UploadStatus.COMPLETED.getCode(), node.getUploadStatus());
        assertEquals(md5, node.getFileMd5());
    }

    @Test
    void mergeChunks_s3CompleteRunsOutsideTransaction() {
        setUpUser(1001L, 1L);
        AtomicBoolean txActiveDuringS3 = new AtomicBoolean(true);
        doAnswer(inv -> {
            txActiveDuringS3.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).completeMultipartUpload(anyString(), anyString());

        UploadInitRequest initReq = new UploadInitRequest();
        initReq.setFileName("tb-merge.txt");
        initReq.setFileSize(5L * 1024 * 1024);
        initReq.setFileMd5(DigestUtil.md5Hex("tb-merge-md5"));
        initReq.setTotalChunks(1);
        initReq.setChunkSize(5L * 1024 * 1024);
        initReq.setParentId(0L);
        UploadInitResponse initResp = uploadService.initChunkedUpload(initReq);
        uploadService.confirmChunk(initResp.getUploadId(), "s3-tb-id", 1);

        UploadMergeRequest merge = new UploadMergeRequest();
        merge.setUploadId(initResp.getUploadId());
        merge.setS3UploadId("s3-tb-id");
        merge.setFileId(initResp.getFileId());

        FileNodeVO vo = uploadService.mergeChunks(merge);

        assertFalse(txActiveDuringS3.get(), "S3 completeMultipartUpload 应在事务外执行");
        assertNotNull(vo.getId());
        FileNode node = fileNodeMapper.selectById(vo.getId());
        assertEquals(UploadStatus.COMPLETED.getCode(), node.getUploadStatus());
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
    }

    @Test
    void simpleUpload_dbFailure_cleansUploadedObject() {
        setUpUser(1001L, 1L);
        byte[] data = "tx-boundary-simple".getBytes(StandardCharsets.UTF_8);
        lastMd5 = DigestUtil.md5Hex(new ByteArrayInputStream(data));
        // DB 事务内事件写入失败 -> 整个落库事务回滚（模拟 DB 写失败）
        doThrow(new RuntimeException("db commit boom"))
                .when(reliableEventPublisher).publishFileIndex(any(FileNode.class), any(FileIndexEvent.ActionType.class));

        MockMultipartFile file = new MockMultipartFile("file", "tb-simple.txt", "text/plain", data);
        assertThrows(RuntimeException.class, () -> uploadService.simpleUpload(0L, file, null));

        // 本次上传的对象应被尽力清理（记录已回滚、无引用），且不误删被引用对象
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadObject(keyCaptor.capture(), any(InputStream.class),
                eq((long) data.length), anyString());
        verify(storageService).deleteObject(keyCaptor.getValue());
        // 事务回滚：file_object / file_node 均无本次记录
        assertEquals(0L, fileObjectMapper.selectCount(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getTenantId, 1L).eq(FileObject::getMd5, lastMd5)).longValue(),
                "DB 失败后对象记录应随事务回滚");
        assertEquals(0L, fileNodeMapper.selectCount(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getTenantId, 1L).eq(FileNode::getName, "tb-simple.txt")).longValue(),
                "DB 失败后节点记录应随事务回滚");
    }

    private UploadInitRequest failingInitRequest(String name, String md5) {
        UploadInitRequest req = new UploadInitRequest();
        req.setFileName(name);
        req.setFileSize(501L * 5 * 1024 * 1024);
        req.setFileMd5(md5);
        req.setTotalChunks(501);
        req.setChunkSize(5L * 1024 * 1024);
        req.setParentId(0L);
        return req;
    }

    private void failOnSecondChunkBatch() {
        FailOnSecondBatchChunk.lastUploadId = null;
        jdbcTemplate.execute("CREATE TRIGGER fail_upload_init_chunk BEFORE INSERT ON file_chunk "
                + "FOR EACH ROW CALL 'com.stcloud.core.service.impl.UploadTransactionBoundaryTest$FailOnSecondBatchChunk'");
    }

    private long rowCount(String table, String md5) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE file_md5 = ?", Long.class, md5);
    }

    @Test
    void initTx01_newUpload_secondChunkBatchSqlFailure_rollsBackAllRows() {
        String md5 = DigestUtil.md5Hex("tb-init-new-" + System.nanoTime());
        failOnSecondChunkBatch();
        AtomicBoolean txDuringInit = new AtomicBoolean(true);
        AtomicBoolean txDuringAbort = new AtomicBoolean(true);
        doAnswer(inv -> {
            txDuringInit.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "s3-tb-id";
        }).when(storageService).initMultipartUpload(anyString());
        doAnswer(inv -> {
            txDuringAbort.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).abortMultipartUpload(anyString(), anyString());

        assertThrows(RuntimeException.class,
                () -> uploadService.initChunkedUpload(failingInitRequest("tb-init-new.txt", md5)));
        assertEquals(0L, rowCount("file_node", md5));
        assertEquals(0L, rowCount("upload_session", md5));
        assertNotNull(FailOnSecondBatchChunk.lastUploadId);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_chunk WHERE upload_id = ?",
                Long.class, FailOnSecondBatchChunk.lastUploadId));
        assertFalse(txDuringInit.get(), "S3 init 应在数据库事务外");
        assertFalse(txDuringAbort.get(), "S3 abort 应在数据库事务外");
        verify(storageService).abortMultipartUpload(anyString(), eq("s3-tb-id"));
    }

    @Test
    void initTx02_replacement_secondChunkBatchSqlFailure_preservesNodeAndVersion() {
        String oldMd5 = DigestUtil.md5Hex("tb-init-original-" + System.nanoTime());
        String newMd5 = DigestUtil.md5Hex("tb-init-replacement-" + System.nanoTime());
        FileNode original = new FileNode();
        original.setTenantId(1L);
        original.setParentId(0L);
        original.setNodeType(1);
        original.setName("tb-init-replace.txt");
        original.setPath("/tb-init-replace.txt");
        original.setFileSize(1024L);
        original.setFileMd5(oldMd5);
        original.setStoragePath("1/old-object");
        original.setStatus(0);
        original.setUploadStatus(UploadStatus.COMPLETED.getCode());
        original.setOwnerId(1001L);
        original.setUploaderId(1001L);
        original.setVersion(0);
        fileNodeMapper.insert(original);
        // 版本服务模拟真实 INSERT；它必须加入 UploadInitCommitManager 的事务。
        doAnswer(inv -> {
            FileNode node = inv.getArgument(0);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbcTemplate.update("INSERT INTO file_version "
                    + "(tenant_id, file_node_id, version_num, file_size, file_md5, storage_path, modifier_id) "
                    + "VALUES (?, ?, 1, ?, ?, ?, ?)", 1L, node.getId(), node.getFileSize(),
                    node.getFileMd5(), node.getStoragePath(), 1001L);
            return null;
        }).when(versionService).snapshotCurrentVersion(any(FileNode.class));
        failOnSecondChunkBatch();
        UploadInitRequest req = failingInitRequest("tb-init-replace.txt", newMd5);
        req.setReplaceFileId(original.getId());

        assertThrows(RuntimeException.class, () -> uploadService.initChunkedUpload(req));
        FileNode after = fileNodeMapper.selectById(original.getId());
        assertNotNull(after);
        assertEquals(oldMd5, after.getFileMd5());
        assertEquals("1/old-object", after.getStoragePath());
        assertEquals(1024L, after.getFileSize());
        assertEquals(UploadStatus.COMPLETED.getCode(), after.getUploadStatus());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_version WHERE file_node_id = ?",
                Long.class, original.getId()));
        assertEquals(0L, rowCount("upload_session", newMd5));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_chunk WHERE file_node_id = ?",
                Long.class, original.getId()));
        assertNotNull(FailOnSecondBatchChunk.lastUploadId);
        verify(storageService).abortMultipartUpload(anyString(), eq("s3-tb-id"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void initTx03_abortFailure_isLoggedWithoutMaskingDbFailure(CapturedOutput output) {
        String md5 = DigestUtil.md5Hex("tb-init-abort-" + System.nanoTime());
        failOnSecondChunkBatch();
        doThrow(new RuntimeException("injected abort failure"))
                .when(storageService).abortMultipartUpload(anyString(), anyString());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> uploadService.initChunkedUpload(failingInitRequest("tb-init-abort.txt", md5)));
        assertFalse(failure.getMessage().contains("injected abort failure"));
        assertTrue(output.getOut().contains("S3 abort 未完成") || output.getErr().contains("S3 abort 未完成"));
        assertEquals(0L, rowCount("file_node", md5));
        assertEquals(0L, rowCount("upload_session", md5));
    }
}
