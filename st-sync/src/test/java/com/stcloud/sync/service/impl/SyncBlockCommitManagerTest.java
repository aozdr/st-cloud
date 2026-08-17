package com.stcloud.sync.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TX-04（F3）块级上传 DB 落库事务方法单元测试。
 * <p>
 * 纯 Mockito 单测：断言 commitBlockUpload 为 {@link Transactional} 独立事务方法，
 * 单次调用内完成全部 DB 写（去重归属 + 节点更新 + 旧对象引用释放 + 版本快照 + 块布局重建
 * + 同步事件 + 差值配额），且不触发任何 S3 调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("块级上传落库事务测试")
class SyncBlockCommitManagerTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;
    private static final long BLOCK_SIZE = 5 * 1024 * 1024L;

    @Mock
    private FileBlockMapper fileBlockMapper;
    @Mock
    private FileNodeMapper fileNodeMapper;
    @Mock
    private FileObjectService fileObjectService;
    @Mock
    private VersionService versionService;
    @Mock
    private UploadEventPublisher uploadEventPublisher;
    @Mock
    private UploadManager uploadManager;

    @InjectMocks
    private SyncBlockCommitManager commitManager;

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

    private FileNode buildNode() {
        FileNode node = new FileNode();
        node.setId(10L);
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
        BlockCheckRequest.BlockHash b0 = new BlockCheckRequest.BlockHash();
        b0.setIndex(0);
        b0.setMd5("block-0");
        b0.setSize(BLOCK_SIZE);
        blocks.add(b0);
        BlockCheckRequest.BlockHash b1 = new BlockCheckRequest.BlockHash();
        b1.setIndex(1);
        b1.setMd5("block-1");
        b1.setSize(1024 * 1024L);
        blocks.add(b1);
        req.setBlocks(blocks);
        return req;
    }

    @Test
    @DisplayName("F3 去重命中：单事务内完成 归属+节点+快照+块布局+事件+差值配额")
    void commitBlockUpload_dedupeHit_writesAllDbState() throws NoSuchMethodException {
        FileNode node = buildNode();
        String mergedPath = "1/1001/md5-new_123";
        BlockUploadRequest request = buildRequest(mergedPath);
        FileObject existing = new FileObject();
        existing.setId(200L);
        existing.setStoragePath("1/md5-existing");
        existing.setRefCount(2);
        when(fileObjectService.acquireByPath(TENANT_ID, "md5-new", request.getFileSize(), mergedPath))
                .thenReturn(existing);

        BlockUploadResponse resp = commitManager.commitBlockUpload(
                node, TENANT_ID, BLOCK_SIZE, 3, 90L, request);

        // 去重归属 + 旧对象引用释放
        verify(fileObjectService).acquireByPath(TENANT_ID, "md5-new", request.getFileSize(), mergedPath);
        verify(fileObjectService).release(90L);
        // 节点更新：指向已存在对象，版本号递增，上传状态已完成
        ArgumentCaptor<FileNode> nodeCaptor = ArgumentCaptor.forClass(FileNode.class);
        verify(fileNodeMapper).updateById(nodeCaptor.capture());
        FileNode updated = nodeCaptor.getValue();
        assertEquals("1/md5-existing", updated.getStoragePath());
        assertEquals(200L, updated.getObjectId());
        assertEquals("md5-new", updated.getFileMd5());
        assertEquals(request.getFileSize(), updated.getFileSize());
        assertEquals(4, updated.getVersion());
        assertEquals(2, updated.getUploadStatus());
        // 版本快照
        verify(versionService).snapshotCurrentVersion(node);
        // 块布局重建：删旧版本记录 + 插入新版本 2 条，存储路径指向最终对象
        verify(fileBlockMapper).delete(any());
        ArgumentCaptor<FileBlock> blockCaptor = ArgumentCaptor.forClass(FileBlock.class);
        verify(fileBlockMapper, times(2)).insert(blockCaptor.capture());
        for (FileBlock fb : blockCaptor.getAllValues()) {
            assertEquals(TENANT_ID, fb.getTenantId());
            assertEquals(10L, fb.getFileNodeId());
            assertEquals(4, fb.getVersion());
            assertEquals("1/md5-existing", fb.getStoragePath());
        }
        // 同步事件 + 差值配额（6MB - 5MB）
        verify(uploadEventPublisher).publishUpdated(node);
        verify(uploadManager).consumeQuota(USER_ID, 20L, 1024 * 1024L);
        // 响应
        assertNotNull(resp);
        assertEquals("10", resp.getFileId());
        assertEquals(4, resp.getVersion());
        // 事务标注：独立 @Transactional 方法（跨 bean 调用经 Spring 代理生效）
        Method method = SyncBlockCommitManager.class.getMethod("commitBlockUpload",
                FileNode.class, Long.class, long.class, int.class, Long.class, BlockUploadRequest.class);
        assertNotNull(method.getAnnotation(Transactional.class),
                "commitBlockUpload 必须为 @Transactional 独立事务方法");
    }

    @Test
    @DisplayName("F3 新建对象：最终路径即合并产物路径，对象ID切换并释放旧引用")
    void commitBlockUpload_newObject_keepsMergedPath() {
        FileNode node = buildNode();
        String mergedPath = "1/1001/md5-new_123";
        BlockUploadRequest request = buildRequest(mergedPath);
        FileObject created = new FileObject();
        created.setId(300L);
        created.setStoragePath(mergedPath);
        created.setRefCount(1);
        when(fileObjectService.acquireByPath(TENANT_ID, "md5-new", request.getFileSize(), mergedPath))
                .thenReturn(created);

        commitManager.commitBlockUpload(node, TENANT_ID, BLOCK_SIZE, 3, 90L, request);

        verify(fileNodeMapper).updateById(node);
        assertEquals(mergedPath, node.getStoragePath());
        assertEquals(300L, node.getObjectId());
        verify(fileObjectService).release(90L);
        verify(fileBlockMapper, times(2)).insert(any(FileBlock.class));
        verify(uploadManager).consumeQuota(USER_ID, 20L, 1024 * 1024L);
    }

    @Test
    @DisplayName("F3 空 md5 兜底：不创建对象记录，节点指向合并产物路径，旧引用仍释放")
    void commitBlockUpload_emptyMd5_keepsMergedPathAndReleasesOld() {
        FileNode node = buildNode();
        String mergedPath = "1/1001/md5-new_123";
        BlockUploadRequest request = buildRequest(mergedPath);
        request.setFileMd5("");
        // acquireByPath 空 md5 返回 null（与改造前 acquire 语义一致）

        commitManager.commitBlockUpload(node, TENANT_ID, BLOCK_SIZE, 3, 90L, request);

        verify(fileNodeMapper).updateById(node);
        assertEquals(mergedPath, node.getStoragePath());
        assertNull(node.getObjectId());
        // 旧对象引用释放：object 为 null 时无条件释放（保留物理对象，可能被版本历史引用）
        verify(fileObjectService).release(90L);
        verify(uploadEventPublisher).publishUpdated(node);
        verify(uploadManager).consumeQuota(USER_ID, 20L, 1024 * 1024L);
    }
}
