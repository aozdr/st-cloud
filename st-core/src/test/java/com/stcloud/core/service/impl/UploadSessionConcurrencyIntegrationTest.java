package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitResult;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.dto.UploadInitResponse;
import com.stcloud.core.dto.UploadMergeRequest;
import com.stcloud.core.mapper.UploadSessionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2/真实 Mapper 并发竞争；测试方法不包事务，以免工作线程看不到初始化记录。 */
@Import(UploadStateMachineIntegrationTest.UploadTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UploadSessionConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UploadService uploadService;
    @Autowired private UploadSessionMapper sessionMapper;
    @Autowired private StorageService storageService;
    @Autowired private FileService fileService;
    @Autowired private SpeedLimitService speedLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setupConcurrentUser() {
        setUpUser(1001L, 1L);
        reset(storageService, fileService, speedLimitService);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (1001, 1, 'session-race', 'x', 1, 0, NULL, 0)");
        when(speedLimitService.resolve()).thenReturn(new SpeedLimitResult(0, 0));
        when(storageService.initMultipartUpload(anyString())).thenReturn("s3-session-race");
        when(fileService.validateAndGetParentPath(anyLong())).thenReturn("/");
        when(fileService.resolveNameConflict(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(fileService.guessContentType(anyString())).thenReturn("text/plain");
        when(fileService.extractSuffix(anyString())).thenReturn("txt");
        doAnswer(inv -> {
            com.stcloud.core.entity.FileNode node = inv.getArgument(0);
            FileNodeVO vo = new FileNodeVO();
            vo.setId(node.getId());
            return vo;
        }).when(fileService).toVO(any());
    }

    @AfterEach
    void cleanupConcurrentUser() {
        jdbcTemplate.update("DELETE FROM file_chunk WHERE upload_id IN (SELECT upload_id FROM upload_session WHERE user_id = 1001)");
        jdbcTemplate.update("DELETE FROM upload_session WHERE user_id = 1001");
        jdbcTemplate.update("DELETE FROM file_node WHERE owner_id = 1001");
        jdbcTemplate.update("DELETE FROM file_object WHERE tenant_id = 1");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = 1001");
    }

    @Test
    void state01_twentyMergesCompleteS3Once() throws Exception {
        UploadInitResponse init = init("session-merge.txt");
        UploadMergeRequest request = mergeRequest(init);
        CountDownLatch s3Entered = new CountDownLatch(1);
        CountDownLatch releaseS3 = new CountDownLatch(1);
        doAnswer(inv -> {
            s3Entered.countDown();
            assertTrue(releaseS3.await(10, TimeUnit.SECONDS));
            return null;
        }).when(storageService).completeMultipartUpload(anyString(), anyString());

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch ready = new CountDownLatch(20);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    withUser();
                    ready.countDown();
                    try {
                        start.await();
                        uploadService.mergeChunks(request);
                    } catch (BusinessException e) {
                        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
                    } finally {
                        clearUser();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(s3Entered.await(10, TimeUnit.SECONDS));
            releaseS3.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            releaseS3.countDown();
            pool.shutdownNow();
        }
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
        assertEquals(2, sessionMapper.selectById(sessionId(init)).getStatus());
    }

    @Test
    void state02_abortDuringClaimedMergeConflicts() throws Exception {
        UploadInitResponse init = init("session-merge-abort.txt");
        CountDownLatch s3Entered = new CountDownLatch(1);
        CountDownLatch releaseS3 = new CountDownLatch(1);
        doAnswer(inv -> {
            s3Entered.countDown();
            assertTrue(releaseS3.await(10, TimeUnit.SECONDS));
            return null;
        }).when(storageService).completeMultipartUpload(anyString(), anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> merge = pool.submit(() -> {
                withUser();
                try { uploadService.mergeChunks(mergeRequest(init)); }
                finally { clearUser(); }
            });
            assertTrue(s3Entered.await(10, TimeUnit.SECONDS));
            jdbcTemplate.update("UPDATE upload_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE upload_id = ?",
                    init.getUploadId());
            BusinessException conflict = assertThrows(BusinessException.class,
                    () -> uploadService.abortUpload(init.getUploadId(), init.getS3UploadId(), init.getFileId()));
            assertEquals(ResultCode.CONFLICT.getCode(), conflict.getCode());
            assertEquals(1, sessionMapper.selectById(sessionId(init)).getStatus(),
                    "过期检查不得把正在合并的会话改为 EXPIRED");
            verify(storageService, never()).abortMultipartUpload(anyString(), anyString());
            releaseS3.countDown();
            merge.get(20, TimeUnit.SECONDS);
        } finally {
            releaseS3.countDown();
            pool.shutdownNow();
        }
        assertEquals(2, sessionMapper.selectById(sessionId(init)).getStatus());
    }

    @Test
    void state03_twentyAbortsCleanupOnce() throws Exception {
        UploadInitResponse init = init("session-abort.txt");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch ready = new CountDownLatch(20);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    withUser();
                    ready.countDown();
                    try {
                        start.await();
                        uploadService.abortUpload(init.getUploadId(), init.getS3UploadId(), init.getFileId());
                    } finally { clearUser(); }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(storageService, times(1)).abortMultipartUpload(anyString(), anyString());
        assertEquals(3, sessionMapper.selectById(sessionId(init)).getStatus());
        assertNull(fileNodeMapper.selectById(init.getFileId()));
    }

    @Test
    void state04_failedSessionRetryHasOneMergeOwner() throws Exception {
        UploadInitResponse init = init("session-retry.txt");
        UploadMergeRequest request = mergeRequest(init);
        doThrow(new RuntimeException("first complete failed"))
                .when(storageService).completeMultipartUpload(anyString(), anyString());
        assertThrows(RuntimeException.class, () -> uploadService.mergeChunks(request));
        assertEquals(4, sessionMapper.selectById(sessionId(init)).getStatus());

        reset(storageService);
        CountDownLatch s3Entered = new CountDownLatch(1);
        CountDownLatch releaseS3 = new CountDownLatch(1);
        doAnswer(inv -> {
            s3Entered.countDown();
            assertTrue(releaseS3.await(10, TimeUnit.SECONDS));
            return null;
        }).when(storageService).completeMultipartUpload(anyString(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    withUser();
                    ready.countDown();
                    try {
                        start.await();
                        uploadService.mergeChunks(request);
                    } catch (BusinessException e) {
                        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
                    } finally {
                        clearUser();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(s3Entered.await(10, TimeUnit.SECONDS));
            assertEquals(1, sessionMapper.selectById(sessionId(init)).getStatus());
            releaseS3.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            releaseS3.countDown();
            pool.shutdownNow();
        }
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
        assertEquals(2, sessionMapper.selectById(sessionId(init)).getStatus());
    }

    @Test
    void genericUploadEndpointsRejectTeamSessionBeforeSideEffects() {
        UploadInitResponse init = init("team-route-guard.txt");
        jdbcTemplate.update("UPDATE upload_session SET space_id = 7 WHERE upload_id = ?", init.getUploadId());
        assertEquals(init.getFileId(), uploadService.getOwnedUploadSessionNodeId(7L, init.getUploadId()));

        assertForbidden(() -> uploadService.getUploadStatus(init.getUploadId(), init.getS3UploadId()));
        assertForbidden(() -> uploadService.getChunkUrl(init.getUploadId(), init.getS3UploadId(), 1, null));
        assertForbidden(() -> uploadService.confirmChunk(init.getUploadId(), init.getS3UploadId(), 1));
        assertForbidden(() -> uploadService.mergeChunks(mergeRequest(init)));
        assertForbidden(() -> uploadService.abortUpload(init.getUploadId(), init.getS3UploadId(), init.getFileId()));
        assertForbidden(() -> uploadService.relayChunk(init.getUploadId(), init.getS3UploadId(), 1,
                new ByteArrayInputStream(new byte[1]), 1));
        assertForbidden(() -> uploadService.relayFinalize(init.getUploadId(), init.getS3UploadId()));
        verify(storageService, never()).completeMultipartUpload(anyString(), anyString());
        verify(storageService, never()).abortMultipartUpload(anyString(), anyString());
    }

    @Test
    void finalizeDbFailureClosesNonResumableSessionAndCleansMergedObject() {
        UploadInitResponse init = init("finalize-db-fail.txt");
        jdbcTemplate.update("UPDATE sys_user SET storage_quota = 1 WHERE id = 1001");

        BusinessException error = assertThrows(BusinessException.class,
                () -> uploadService.mergeChunks(mergeRequest(init)));

        assertEquals(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), error.getCode());
        assertEquals(3, sessionMapper.selectById(sessionId(init)).getStatus(),
                "S3 complete 后 DB 失败的会话不得卡在 MERGING 或误判可续传");
        assertNull(fileNodeMapper.selectById(init.getFileId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_chunk WHERE upload_id = ?", Integer.class, init.getUploadId()));
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
        verify(storageService, times(1)).deleteObject(anyString());
        verify(storageService, never()).abortMultipartUpload(anyString(), anyString());
    }

    private void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(ResultCode.FORBIDDEN.getCode(), error.getCode());
    }

    private UploadInitResponse init(String name) {
        UploadInitRequest request = new UploadInitRequest();
        request.setFileName(name);
        request.setFileSize(5L * 1024 * 1024);
        request.setFileMd5(DigestUtil.md5Hex(name));
        request.setTotalChunks(1);
        request.setChunkSize(5L * 1024 * 1024);
        request.setParentId(0L);
        return uploadService.initChunkedUpload(request);
    }

    private UploadMergeRequest mergeRequest(UploadInitResponse init) {
        UploadMergeRequest request = new UploadMergeRequest();
        request.setUploadId(init.getUploadId());
        request.setS3UploadId(init.getS3UploadId());
        request.setFileId(init.getFileId());
        return request;
    }

    private Long sessionId(UploadInitResponse init) {
        return jdbcTemplate.queryForObject("SELECT id FROM upload_session WHERE upload_id = ?", Long.class, init.getUploadId());
    }

    private void withUser() { setUpUser(1001L, 1L); }
    private void clearUser() { UserContext.clear(); TenantContext.clear(); }
}
