package com.stcloud.sync.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.response.Result;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockUploadRequest;
import com.stcloud.sync.dto.BlockUploadResponse;
import com.stcloud.sync.entity.FileBlock;
import com.stcloud.sync.mapper.FileBlockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TX-04（F3）blockUpload 事务边界单元测试。
 * <p>
 * 纯 Mockito 单测：断言 S3 uploadPartCopy / completeMultipartUpload 全部在事务外执行，
 * DB 落库委托 {@link SyncBlockCommitManager} 独立事务方法，去重命中清理在事务提交后执行
 * （与 mergeChunks 的 finalizeMerge 清理模式一致），S3 失败不触发 DB 落库与清理。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("blockUpload 事务边界测试")
class SyncBlockServiceImplBlockUploadTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;
    private static final long BLOCK_SIZE = 5 * 1024 * 1024L;

    @Mock
    private FileBlockMapper fileBlockMapper;
    @Mock
    private FileService fileService;
    @Mock
    private StorageService storageService;
    @Mock
    private UploadStorageManager uploadStorageManager;
    @Mock
    private SyncBlockCommitManager syncBlockCommitManager;

    @InjectMocks
    private SyncBlockServiceImpl syncBlockService;

    @BeforeEach
    void setUpContext() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .username("test-user")
                .build());
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    private FileNode buildFileNode(Long nodeId) {
        FileNode node = new FileNode();
        node.setId(nodeId);
        node.setTenantId(TENANT_ID);
        node.setNodeType(1);
        node.setName("big.bin");
        node.setPath("/big.bin");
        node.setFileSize(BLOCK_SIZE);
        node.setFileMd5("md5-old");
        node.setVersion(3);
        node.setStoragePath("1/1001/old.bin");
        node.setObjectId(90L);
        node.setSpaceId(20L);
        return node;
    }

    private BlockUploadRequest buildRequest(String mergedPath) {
        BlockUploadRequest req = new BlockUploadRequest();
        req.setFileNodeId(10L);
        req.setS3UploadId("s3-upload-id-tx04");
        req.setStoragePath(mergedPath);
        req.setFileMd5("md5-new");
        req.setFileSize(BLOCK_SIZE + 1024 * 1024L);
        req.setBlockSize(BLOCK_SIZE);
        req.setTotalBlocks(2);
        List<BlockCheckRequest.BlockHash> blocks = new ArrayList<>();
        BlockCheckRequest.BlockHash reusable = new BlockCheckRequest.BlockHash();
        reusable.setIndex(0);
        reusable.setMd5("block-0");
        reusable.setSize(BLOCK_SIZE);
        blocks.add(reusable);
        BlockCheckRequest.BlockHash newBlock = new BlockCheckRequest.BlockHash();
        newBlock.setIndex(1);
        newBlock.setMd5("block-1");
        newBlock.setSize(1024 * 1024L);
        blocks.add(newBlock);
        req.setBlocks(blocks);
        return req;
    }

    private FileBlock buildServerBlock(int index, String md5, long size) {
        FileBlock fb = new FileBlock();
        fb.setFileNodeId(10L);
        fb.setVersion(3);
        fb.setBlockIndex(index);
        fb.setBlockMd5(md5);
        fb.setBlockSize(size);
        fb.setStoragePath("1/1001/old.bin");
        return fb;
    }

    private void stubCommitReturn(FileNode node, String finalPath, long newVersion) {
        when(syncBlockCommitManager.commitBlockUpload(any(), any(), anyLong(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    // 模拟真实落库后的节点状态：存储路径指向最终对象，版本号递增
                    FileNode n = invocation.getArgument(0);
                    n.setStoragePath(finalPath);
                    n.setObjectId(200L);
                    n.setVersion((int) newVersion);
                    BlockUploadResponse resp = new BlockUploadResponse();
                    resp.setFileId(String.valueOf(n.getId()));
                    resp.setVersion(n.getVersion());
                    return resp;
                });
    }

    @Test
    @DisplayName("F3 S3 复制/合并全部在事务外，DB 落库委托独立事务方法")
    void blockUpload_s3CallsOutsideTransaction_dbCommitDelegated() {
        FileNode node = buildFileNode(10L);
        String mergedPath = "1/1001/md5-new_123";
        BlockUploadRequest request = buildRequest(mergedPath);
        when(fileService.getNodeByIdAndOwner(10L)).thenReturn(node);
        when(fileBlockMapper.selectList(any())).thenReturn(
                Collections.singletonList(buildServerBlock(0, "block-0", BLOCK_SIZE)));
        // 未去重命中：最终路径即本次合并产物路径
        stubCommitReturn(node, mergedPath, 4L);

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用前不应存在事务（纯 Mockito 测试无事务管理器）");

        Result<BlockUploadResponse> result = syncBlockService.blockUpload(request);

        assertNotNull(result.getData());
        assertEquals("10", result.getData().getFileId());
        assertEquals(4, result.getData().getVersion());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "blockUpload 整体不应开启 DB 事务");

        // 可复用块执行一次 S3 复制，随后 complete；全部在事务外
        verify(storageService).uploadPartCopy(eq("1/1001/old.bin"), eq(0L), eq(BLOCK_SIZE - 1),
                eq(mergedPath), eq("s3-upload-id-tx04"), eq(1));
        verify(storageService).completeMultipartUpload(mergedPath, "s3-upload-id-tx04");
        // DB 落库收敛进独立事务方法：调用方只读派生块布局 + S3 + 委托落库
        verify(syncBlockCommitManager).commitBlockUpload(eq(node), eq(TENANT_ID),
                eq(BLOCK_SIZE), eq(3), eq(90L), eq(request));
        // 未去重命中：合并产物被对象记录引用，不清理
        verify(uploadStorageManager, never()).deleteObjectQuietly(anyString());

        InOrder order = inOrder(storageService, syncBlockCommitManager);
        order.verify(storageService).uploadPartCopy(anyString(), anyLong(), anyLong(),
                anyString(), anyString(), anyInt());
        order.verify(storageService).completeMultipartUpload(anyString(), anyString());
        order.verify(syncBlockCommitManager).commitBlockUpload(any(), any(), anyLong(),
                anyInt(), any(), any());
    }

    @Test
    @DisplayName("F3 去重命中：合并产物清理发生在事务提交之后且仅一次")
    void blockUpload_dedupeHit_cleansMergedObjectAfterCommit() {
        FileNode node = buildFileNode(10L);
        String mergedPath = "1/1001/md5-new_123";
        BlockUploadRequest request = buildRequest(mergedPath);
        when(fileService.getNodeByIdAndOwner(10L)).thenReturn(node);
        when(fileBlockMapper.selectList(any())).thenReturn(Collections.emptyList());
        // 去重命中：最终路径切换为已存在对象路径（不同于合并产物路径）
        stubCommitReturn(node, "1/md5-existing", 4L);

        syncBlockService.blockUpload(request);

        // 清理在事务提交（commit 调用返回）之后执行，且仅清理本次合并产物路径
        InOrder order = inOrder(syncBlockCommitManager, uploadStorageManager);
        order.verify(syncBlockCommitManager).commitBlockUpload(any(), any(), anyLong(),
                anyInt(), any(), any());
        order.verify(uploadStorageManager).deleteObjectQuietly(mergedPath);
        verify(uploadStorageManager, never()).deleteObjectQuietly("1/md5-existing");
    }

    @Test
    @DisplayName("F3 S3 completeMultipartUpload 失败：直接抛错，不落库、不清理")
    void blockUpload_s3CompleteFailure_skipsDbCommitAndCleanup() {
        FileNode node = buildFileNode(10L);
        BlockUploadRequest request = buildRequest("1/1001/md5-new_123");
        when(fileService.getNodeByIdAndOwner(10L)).thenReturn(node);
        when(fileBlockMapper.selectList(any())).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("S3 complete failed"))
                .when(storageService).completeMultipartUpload(anyString(), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> syncBlockService.blockUpload(request));
        assertEquals("S3 complete failed", ex.getMessage());
        // S3 失败路径：不开启事务、不进入 DB 落库、不执行任何清理
        verify(syncBlockCommitManager, never()).commitBlockUpload(any(), any(), anyLong(),
                anyInt(), any(), any());
        verify(uploadStorageManager, never()).deleteObjectQuietly(anyString());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "S3 失败路径不应开启 DB 事务");
    }

    @Test
    @DisplayName("F3 S3 uploadPartCopy 失败：直接抛错，不执行 complete 与落库")
    void blockUpload_s3CopyFailure_skipsCompleteAndDbCommit() {
        FileNode node = buildFileNode(10L);
        BlockUploadRequest request = buildRequest("1/1001/md5-new_123");
        when(fileService.getNodeByIdAndOwner(10L)).thenReturn(node);
        when(fileBlockMapper.selectList(any())).thenReturn(
                Collections.singletonList(buildServerBlock(0, "block-0", BLOCK_SIZE)));
        doThrow(new RuntimeException("S3 copy failed"))
                .when(storageService).uploadPartCopy(anyString(), anyLong(), anyLong(),
                        anyString(), anyString(), anyInt());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> syncBlockService.blockUpload(request));
        assertEquals("S3 copy failed", ex.getMessage());
        verify(storageService, never()).completeMultipartUpload(anyString(), anyString());
        verify(syncBlockCommitManager, never()).commitBlockUpload(any(), any(), anyLong(),
                anyInt(), any(), any());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "S3 复制失败路径不应开启 DB 事务");
    }
}
