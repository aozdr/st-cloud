package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitResult;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.config.UploadRelayConfig;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.dto.UploadInitResponse;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileChunkMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.RelayBufferManager;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中转上传（relay）集成测试（TASK-20260813-code-review-fix-04）。
 * 覆盖 testcases.md TC-001~013：模式判定 / relayChunkSize 边界 / pacing / 攒批 uploadPart /
 * 末片 / 权限（chunk+finalize）/ 失败 abort / 超时清理 / 重复 seq 幂等 / 客户端自限速 / simpleUpload 限速。
 * 使用 H2 + 真实 Mapper；S3/文件/事件以 Mock 隔离，RelayBufferManager 为真实实现（临时目录隔离）。
 */
@Import(RelayUploadIntegrationTest.RelayTestConfig.class)
class RelayUploadIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RelayTestConfig {
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
        FileObjectService fileObjectService() {
            return new FileObjectServiceImpl();
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
        UploadRelayConfig uploadRelayConfig() {
            UploadRelayConfig config = new UploadRelayConfig();
            try {
                config.setTempDir(Files.createTempDirectory("stcloud-relay-test").toString());
            } catch (Exception e) {
                throw new IllegalStateException("创建中转测试临时目录失败", e);
            }
            // 超时阈值调小，便于 TC-010 触发定时清理
            config.setSessionTimeoutMs(200L);
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
    private StorageService storageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private SpeedLimitService speedLimitService;

    @Autowired
    private FileNodeMapper fileNodeMapper;

    @Autowired
    private FileChunkMapper fileChunkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UploadRelayConfig relayConfig;

    @Autowired
    private RelayBufferManager relayBufferManager;

    @BeforeEach
    void setUp() {
        setUpUser(1001L, 1L);
        Mockito.reset(storageService);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (1001, 1, 'relay-test', 'x', 1, 0, NULL, 0)");
        when(storageService.initMultipartUpload(anyString())).thenReturn("s3-test-upload-id");
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
        // 默认服务端不限速
        when(speedLimitService.resolve()).thenReturn(new SpeedLimitResult(0, 0));
    }

    private UploadInitResponse init(String name, long fileSize, int totalChunks, Integer clientLimit) {
        UploadInitRequest req = new UploadInitRequest();
        req.setFileName(name);
        req.setFileSize(fileSize);
        req.setFileMd5("md5-" + name);
        req.setTotalChunks(totalChunks);
        req.setChunkSize(5L * 1024 * 1024);
        req.setParentId(0L);
        req.setClientLimit(clientLimit);
        return uploadService.initChunkedUpload(req);
    }

    private void postChunk(String uploadId, byte[] data, int seq) {
        uploadService.relayChunk(uploadId, "s3-test-upload-id", seq,
                new ByteArrayInputStream(data), data.length);
    }

    private void setServerLimit(int kb) {
        when(speedLimitService.resolve()).thenReturn(new SpeedLimitResult(kb, 0));
    }

    @Test
    void tc001_rateZero_returnsDirect() {
        UploadInitResponse resp = init("tc001.txt", 5L * 1024 * 1024, 1, null);
        assertEquals("direct", resp.getTransferMode());
        assertNull(resp.getRelayChunkSize());
        assertNull(resp.getRelayRateKb());
    }

    @Test
    void tc002_lowRate_returnsRelayWithClampedChunkSize() {
        setServerLimit(100); // 100KB/s < 5MB
        UploadInitResponse resp = init("tc002.txt", 6L * 1024 * 1024, 2, null);
        assertEquals("relay", resp.getTransferMode());
        assertEquals(204800L, resp.getRelayChunkSize()); // max(8192, min(102400*2, 1MB))
        assertEquals(100L, resp.getRelayRateKb());
    }

    @Test
    void tc003_relayChunkSizeBoundaries() {
        // 1KB/s：下限 8KB 兜底
        setServerLimit(1);
        assertEquals(8192L, init("tc003a.txt", 5L * 1024 * 1024, 1, null).getRelayChunkSize());
        // 4MB/s：rate<5MB 仍 relay，上限 1MB 兜底
        setServerLimit(4096);
        UploadInitResponse r = init("tc003b.txt", 5L * 1024 * 1024, 1, null);
        assertEquals("relay", r.getTransferMode());
        assertEquals(1024L * 1024L, r.getRelayChunkSize());
        // 5MB/s：rate>=chunkSize 走 direct
        setServerLimit(5120);
        assertEquals("direct", init("tc003c.txt", 5L * 1024 * 1024, 1, null).getTransferMode());
    }

    @Test
    void tc004_pacingThrottlesSecondChunk() {
        setServerLimit(1); // 1KB/s，relayChunkSize=8KB
        byte[] chunk = new byte[8192];
        UploadInitResponse resp = init("tc004.txt", 16384, 1, null);
        // 首块：桶容量 max(8192,1024)=8192，允许突发 ≤ relayChunkSize（设计容忍）
        postChunk(resp.getUploadId(), chunk, 1);
        // 第二块：需重新攒 8192 令牌 @1KB/s ≈ 8s，验证真实节流
        long t0 = System.nanoTime();
        postChunk(resp.getUploadId(), chunk, 2);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs >= 4000, "第二块应被 pacing 阻塞，实际 " + elapsedMs + "ms");
    }

    @Test
    void tc005_accumulatedToPartMinSize_triggersUploadPart() {
        setServerLimit(4096); // 4MB/s，relayChunkSize=1MB
        long partMin = relayConfig.getPartMinSize(); // 5MB
        long chunkSize = 1024L * 1024L;
        long fileSize = partMin + chunkSize; // 6MB
        UploadInitResponse resp = init("tc005.txt", fileSize, 6, null);
        byte[] chunk = new byte[(int) chunkSize];
        // 前 5 块累计 5MB：第 5 块触发 part1
        for (int seq = 1; seq <= 5; seq++) {
            postChunk(resp.getUploadId(), chunk, seq);
        }
        verify(storageService, times(1)).uploadPart(anyString(), anyString(), eq(1), any(InputStream.class), anyLong());
        verify(storageService, never()).uploadPart(anyString(), anyString(), eq(2), any(InputStream.class), anyLong());
        // 第 6 块 1MB 进缓冲，finalize 上传末片 part2
        postChunk(resp.getUploadId(), chunk, 6);
        uploadService.relayFinalize(resp.getUploadId(), "s3-test-upload-id");
        verify(storageService, times(1)).uploadPart(anyString(), anyString(), eq(2), any(InputStream.class), anyLong());
    }

    @Test
    void tc006_lastPartUnderMinSize_singleFinalizePart() {
        setServerLimit(4096);
        long fileSize = 3L * 1024 * 1024; // 3MB < 5MB：仅末片
        UploadInitResponse resp = init("tc006.txt", fileSize, 1, null);
        byte[] chunk = new byte[1024 * 1024];
        for (int seq = 1; seq <= 3; seq++) {
            postChunk(resp.getUploadId(), chunk, seq);
        }
        uploadService.relayFinalize(resp.getUploadId(), "s3-test-upload-id");
        verify(storageService, times(1)).uploadPart(anyString(), anyString(), eq(1), any(InputStream.class), anyLong());
    }

    @Test
    void tc007_tc008_nonOwnerDeniedOnChunkAndFinalize() {
        setServerLimit(100);
        UploadInitResponse resp = init("perm.txt", 100L * 1024, 1, null);
        // 用户 B（非 owner、非租户管理员）
        setUpUser(2002L, 1L);
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> postChunk(resp.getUploadId(), new byte[8192], 1));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), ex1.getCode());
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> uploadService.relayFinalize(resp.getUploadId(), "s3-test-upload-id"));
        assertEquals(ResultCode.PERMISSION_DENIED.getCode(), ex2.getCode());
    }

    @Test
    void tc009_finalizeFailure_abortsAndCleansTemp() {
        setServerLimit(4096);
        UploadInitResponse resp = init("tc009.txt", 3L * 1024 * 1024, 1, null);
        byte[] chunk = new byte[1024 * 1024];
        for (int seq = 1; seq <= 3; seq++) {
            postChunk(resp.getUploadId(), chunk, seq);
        }
        doThrow(new RuntimeException("s3 complete boom"))
                .when(storageService).completeMultipartUpload(anyString(), anyString());
        assertThrows(RuntimeException.class,
                () -> uploadService.relayFinalize(resp.getUploadId(), "s3-test-upload-id"));
        verify(storageService, atLeastOnce()).abortMultipartUpload(anyString(), anyString());
        assertEquals(0L, relayBufferManager.getRate(resp.getUploadId()), "失败后会话（临时文件）应已清理");
    }

    @Test
    void tc010_timeoutCleanup_abortsAndRemovesSession() throws Exception {
        setServerLimit(100);
        UploadInitResponse resp = init("tc010.txt", 100L * 1024, 1, null);
        assertTrue(relayBufferManager.getRate(resp.getUploadId()) > 0L, "会话应已创建");
        Thread.sleep(400); // 超过测试配置的超时阈值 200ms
        relayBufferManager.scheduledCleanup();
        assertEquals(0L, relayBufferManager.getRate(resp.getUploadId()), "超时会话应被清理");
        verify(storageService, atLeastOnce()).abortMultipartUpload(anyString(), anyString());
    }

    @Test
    void tc011_duplicateSeq_idempotentNoDuplicateBytes() {
        setServerLimit(4096);
        long fileSize = 1024L * 1024; // relayChunkSize=1MB，单块正好不超限
        UploadInitResponse resp = init("tc011.txt", fileSize, 1, null);
        byte[] chunk = new byte[(int) fileSize]; // 1MB <= relayChunkSize，单 seq
        postChunk(resp.getUploadId(), chunk, 1);
        postChunk(resp.getUploadId(), chunk, 1); // 重复 seq：应忽略
        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        uploadService.relayFinalize(resp.getUploadId(), "s3-test-upload-id");
        verify(storageService).uploadPart(anyString(), anyString(), eq(1), any(InputStream.class), sizeCaptor.capture());
        assertEquals(fileSize, sizeCaptor.getValue().longValue(), "重复 seq 不得重复写字节");
    }

    @Test
    void tc012_clientSelfLimit_triggersRelay() {
        setServerLimit(0); // 服务端不限速
        UploadInitResponse resp = init("tc012.txt", 6L * 1024 * 1024, 2, 100); // clientLimit=100KB/s
        assertEquals("relay", resp.getTransferMode());
        assertEquals(204800L, resp.getRelayChunkSize());
        assertEquals(100L, resp.getRelayRateKb());
    }

    @Test
    void tc013_simpleUpload_pacedByServerLimit() throws Exception {
        setServerLimit(100); // 100KB/s
        // mock 需真正读取流，pacing 才生效（否则不产生节流）
        doAnswer(inv -> {
            InputStream in = inv.getArgument(1);
            byte[] buf = new byte[8192];
            while (in.read(buf) != -1) {
                // 消费整个流，触发 pacedInputStream 的限速读取
            }
            return null;
        }).when(storageService).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        byte[] data = new byte[200 * 1024]; // 200KB，预期约 2s
        MockMultipartFile file = new MockMultipartFile("file", "tc013.txt", "text/plain", data);
        long t0 = System.nanoTime();
        uploadService.simpleUpload(0L, file, null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs >= 1000, "simpleUpload 应受 pacing 节流，实际 " + elapsedMs + "ms");
        verify(storageService, atLeastOnce()).uploadObject(anyString(), any(InputStream.class),
                eq((long) data.length), anyString());
    }

    @Test
    void relay_partUpload_marksChunkUploadedInDb() {
        setServerLimit(4096);
        long partMin = relayConfig.getPartMinSize();
        long fileSize = partMin + 1024L * 1024L; // 6MB
        UploadInitResponse resp = init("mark.txt", fileSize, 6, null);
        byte[] chunk = new byte[1024 * 1024];
        for (int seq = 1; seq <= 5; seq++) {
            postChunk(resp.getUploadId(), chunk, seq);
        }
        // 第 1 个 5MB part 已触发：file_chunk 记录应落库为已上传(1)（impact.md 遗留）
        FileChunk first = fileChunkMapper.selectOne(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, resp.getUploadId())
                .eq(FileChunk::getChunkIndex, 1));
        assertNotNull(first);
        assertEquals(1, first.getStatus(), "触发 uploadPart 后 file_chunk 状态应置为已上传(1)");
    }
}
