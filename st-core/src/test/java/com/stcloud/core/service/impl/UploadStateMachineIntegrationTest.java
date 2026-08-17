package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.ratelimit.SpeedLimitResult;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.config.UploadRelayConfig;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.dto.UploadInitResponse;
import com.stcloud.core.dto.UploadMergeRequest;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileChunkMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import com.stcloud.core.service.impl.upload.RelayBufferManager;
import com.stcloud.common.ratelimit.SpeedLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 上传状态机集成测试（TASK-002）。
 * 验证：INIT->UPLOADING 初始化、confirm 分片状态落库、merge 成功/幂等/失败恢复、abort 清理与守卫。
 * 使用 H2 + 真实 Mapper；S3/文件/版本/事件等外部协作以 Mock 隔离，聚焦状态机与幂等语义。
 */
@Import(UploadStateMachineIntegrationTest.UploadTestConfig.class)
class UploadStateMachineIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class UploadTestConfig {
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
        UploadRelayConfig uploadRelayConfig() {
            UploadRelayConfig config = new UploadRelayConfig();
            try {
                config.setTempDir(Files.createTempDirectory("stcloud-relay-utm").toString());
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
        UploadService uploadService() {
            return new UploadServiceImpl();
        }
    }

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FileNodeMapper fileNodeMapper;

    @Autowired
    private FileChunkMapper fileChunkMapper;

    @Autowired
    private StorageService storageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private SpeedLimitService speedLimitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        setUpUser(1001L, 1L);
        Mockito.reset(storageService);
        // 默认不限速（direct 模式），保持既有状态机测试语义
        when(speedLimitService.resolve()).thenReturn(new SpeedLimitResult(0, 0));
        // 测试用户行：配额 NULL=不限，满足 TASK-003 原子扣减成功
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (1001, 1, 'up-test', 'x', 1, 0, NULL, 0)");
        // S3 默认行为：可初始化、无已传分片、合并/中止为空操作
        when(storageService.initMultipartUpload(anyString())).thenReturn("s3-test-upload-id");
        when(storageService.listUploadedParts(anyString(), anyString())).thenReturn(Collections.emptyList());
        // FileService 协作桩：路径/命名/类型推断/VO 转换
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
    }

    private UploadInitResponse init(String name, int totalChunks, String md5) {
        UploadInitRequest req = new UploadInitRequest();
        req.setFileName(name);
        req.setFileSize(1024L * totalChunks);
        req.setFileMd5(md5);
        req.setTotalChunks(totalChunks);
        req.setChunkSize(1024L);
        req.setParentId(0L);
        return uploadService.initChunkedUpload(req);
    }

    private void confirmAll(String uploadId, int totalChunks) {
        for (int i = 1; i <= totalChunks; i++) {
            uploadService.confirmChunk(uploadId, "s3-test-upload-id", i);
        }
    }

    private List<FileChunk> chunks(String uploadId) {
        return fileChunkMapper.selectList(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .orderByAsc(FileChunk::getChunkIndex));
    }

    @Test
    void init_createsNodeAndChunksInUploadingState() {
        UploadInitResponse resp = init("stm-init.txt", 3, "md5-stm-init");
        assertNotNull(resp.getUploadId());
        assertNotNull(resp.getS3UploadId());
        assertNotNull(resp.getFileId());

        FileNode node = fileNodeMapper.selectById(resp.getFileId());
        assertNotNull(node);
        assertEquals(UploadStatus.UPLOADING.getCode(), node.getUploadStatus());
        assertEquals("md5-stm-init", node.getFileMd5());

        List<FileChunk> list = chunks(resp.getUploadId());
        assertEquals(3, list.size());
        assertTrue(list.stream().allMatch(c -> c.getStatus() == 0), "初始化后全部分片应为待上传(0)");
    }

    @Test
    void confirmChunk_persistsUploadedStatusAndIsIdempotent() {
        UploadInitResponse resp = init("stm-confirm.txt", 2, "md5-stm-confirm");
        uploadService.confirmChunk(resp.getUploadId(), "s3-test-upload-id", 1);

        FileChunk c1 = chunks(resp.getUploadId()).get(0);
        assertEquals(1, c1.getStatus(), "confirm 后分片应落库为已上传(1)");

        // 重复 confirm 幂等：不报错，状态保持
        uploadService.confirmChunk(resp.getUploadId(), "s3-test-upload-id", 1);
        FileChunk again = chunks(resp.getUploadId()).get(0);
        assertEquals(1, again.getStatus());
    }

    @Test
    void merge_successMarksCompletedAndIsIdempotent() {
        UploadInitResponse resp = init("stm-merge.txt", 2, "md5-stm-merge");
        confirmAll(resp.getUploadId(), 2);

        UploadMergeRequest merge = new UploadMergeRequest();
        merge.setUploadId(resp.getUploadId());
        merge.setS3UploadId("s3-test-upload-id");
        merge.setFileId(resp.getFileId());

        var vo1 = uploadService.mergeChunks(merge);
        FileNode node = fileNodeMapper.selectById(resp.getFileId());
        assertEquals(UploadStatus.COMPLETED.getCode(), node.getUploadStatus());
        // 分片标记为已合并(2)且保留（支撑幂等）
        assertTrue(chunks(resp.getUploadId()).stream().allMatch(c -> c.getStatus() == 2));

        // 重复 merge 幂等：同一节点、不重复合并、不产生重复节点
        var vo2 = uploadService.mergeChunks(merge);
        assertEquals(vo1.getId(), vo2.getId());
        long nodeCount = fileNodeMapper.selectCount(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getName, "stm-merge.txt"));
        assertEquals(1L, nodeCount, "重复 merge 不得产生重复节点");
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
    }

    @Test
    void merge_failureKeepsFailedNodeForRetry() {
        UploadInitResponse resp = init("stm-fail.txt", 2, "md5-stm-fail");
        confirmAll(resp.getUploadId(), 2);

        UploadMergeRequest merge = new UploadMergeRequest();
        merge.setUploadId(resp.getUploadId());
        merge.setS3UploadId("s3-test-upload-id");
        merge.setFileId(resp.getFileId());

        // 首次合并失败：新建上传保留节点与分片，标记 FAILED 供重试
        doThrow(new RuntimeException("s3 merge boom"))
                .when(storageService).completeMultipartUpload(anyString(), anyString());
        assertThrows(RuntimeException.class, () -> uploadService.mergeChunks(merge));

        FileNode failed = fileNodeMapper.selectById(resp.getFileId());
        assertNotNull(failed, "失败后节点应保留供重试");
        assertEquals(UploadStatus.FAILED.getCode(), failed.getUploadStatus());
        assertEquals(2, chunks(resp.getUploadId()).size(), "失败后分片应保留供断点续传");

        // 恢复 S3 后重试合并：同一节点转为 COMPLETED（失败可恢复）
        doNothing().when(storageService).completeMultipartUpload(anyString(), anyString());
        var vo = uploadService.mergeChunks(merge);
        assertEquals(resp.getFileId(), vo.getId());
        FileNode recovered = fileNodeMapper.selectById(resp.getFileId());
        assertEquals(UploadStatus.COMPLETED.getCode(), recovered.getUploadStatus());
    }

    @Test
    void abort_cleansPendingUploadAndIsIdempotent() {
        UploadInitResponse resp = init("stm-abort.txt", 3, "md5-stm-abort");
        uploadService.abortUpload(resp.getUploadId(), "s3-test-upload-id", resp.getFileId());

        assertNull(fileNodeMapper.selectById(resp.getFileId()), "新建上传中止后节点应删除");
        assertTrue(chunks(resp.getUploadId()).isEmpty(), "中止后分片记录应清理");

        // 重复 abort 幂等：不报错
        assertDoesNotThrow(() -> uploadService.abortUpload(resp.getUploadId(), "s3-test-upload-id", resp.getFileId()));
    }

    @Test
    void abort_completedUploadIsNoop() {
        UploadInitResponse resp = init("stm-done.txt", 2, "md5-stm-done");
        confirmAll(resp.getUploadId(), 2);
        UploadMergeRequest merge = new UploadMergeRequest();
        merge.setUploadId(resp.getUploadId());
        merge.setS3UploadId("s3-test-upload-id");
        merge.setFileId(resp.getFileId());
        uploadService.mergeChunks(merge);

        // 已完成上传不允许 abort（幂等守卫，防止误删已完成文件）
        uploadService.abortUpload(resp.getUploadId(), "s3-test-upload-id", resp.getFileId());
        FileNode node = fileNodeMapper.selectById(resp.getFileId());
        assertNotNull(node);
        assertEquals(UploadStatus.COMPLETED.getCode(), node.getUploadStatus());
    }

    @Test
    void resume_afterPartialConfirm_completesRemainingAndMerges() {
        UploadInitResponse resp = init("stm-resume.txt", 3, "md5-stm-resume");
        // 断点续传场景：中断前仅 1 号分片已上传
        uploadService.confirmChunk(resp.getUploadId(), "s3-test-upload-id", 1);

        // 续传依据：已上传分片状态已落库（1 号 status=1，其余待上传）
        List<FileChunk> list = chunks(resp.getUploadId());
        assertEquals(1, list.get(0).getStatus(), "1 号分片应已上传");
        assertEquals(0, list.get(1).getStatus(), "2 号分片应待上传");
        assertEquals(0, list.get(2).getStatus(), "3 号分片应待上传");

        // 恢复后续传剩余分片并合并
        uploadService.confirmChunk(resp.getUploadId(), "s3-test-upload-id", 2);
        uploadService.confirmChunk(resp.getUploadId(), "s3-test-upload-id", 3);
        UploadMergeRequest merge = new UploadMergeRequest();
        merge.setUploadId(resp.getUploadId());
        merge.setS3UploadId("s3-test-upload-id");
        merge.setFileId(resp.getFileId());
        var vo = uploadService.mergeChunks(merge);
        FileNode node = fileNodeMapper.selectById(resp.getFileId());
        assertEquals(UploadStatus.COMPLETED.getCode(), node.getUploadStatus(), "续传后应完成合并");
        assertNotNull(vo);
        verify(storageService, times(1)).completeMultipartUpload(anyString(), anyString());
    }
}
