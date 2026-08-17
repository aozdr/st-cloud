package com.stcloud.sync.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.response.Result;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import com.stcloud.sync.mapper.FileBlockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TX-02（F1-2）blockCheck 去事务单元测试。
 * <p>
 * 纯 Mockito 单测（无 Spring 事务管理器）：通过
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} 断言
 * blockCheck 不再开启 DB 事务，并覆盖 S3 initMultipartUpload 失败直接抛错的路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("blockCheck 去事务测试")
class SyncBlockServiceImplNoTransactionTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;

    @Mock
    private FileBlockMapper fileBlockMapper;
    @Mock
    private FileNodeMapper fileNodeMapper;
    @Mock
    private FileService fileService;
    @Mock
    private StorageService storageService;
    @Mock
    private FileObjectService fileObjectService;
    @Mock
    private VersionService versionService;
    @Mock
    private UploadEventPublisher uploadEventPublisher;
    @Mock
    private UploadManager uploadManager;
    @Mock
    private UploadStorageManager uploadStorageManager;

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
        node.setVersion(3);
        node.setStoragePath("1/1001/old.bin");
        return node;
    }

    private BlockCheckRequest buildRequest(Long nodeId) {
        BlockCheckRequest req = new BlockCheckRequest();
        req.setFileNodeId(nodeId);
        req.setFileMd5("md5-new-version");
        req.setFileSize(5 * 1024 * 1024L);
        req.setBlockSize(5 * 1024 * 1024L);
        req.setBlocks(Collections.emptyList());
        return req;
    }

    @Test
    @DisplayName("F1-2 blockCheck 正常执行且不开启 DB 事务（S3 init 在事务外）")
    void blockCheck_doesNotOpenTransaction() {
        FileNode node = buildFileNode(10L);
        when(fileService.getNodeByIdAndOwner(10L)).thenReturn(node);
        when(fileBlockMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(storageService.initMultipartUpload(any())).thenReturn("s3-upload-id-tx02");

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用前不应存在事务（纯 Mockito 测试无事务管理器）");

        Result<BlockCheckResponse> result = syncBlockService.blockCheck(buildRequest(10L));

        assertTrue(result.getData() != null, "blockCheck 应返回响应");
        assertEquals("s3-upload-id-tx02", result.getData().getS3UploadId());
        assertTrue(result.getData().getStoragePath().startsWith("1/1001/md5-new-version_"),
                "新版本存储路径应按 租户/用户/md5_时间戳 生成");
        assertTrue(result.getData().getReusableBlocks().isEmpty(), "无可复用块");
        assertTrue(result.getData().getMissingBlocks().isEmpty(), "无缺失块");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "blockCheck 不应开启 DB 事务");
    }

    @Test
    @DisplayName("F1-2 S3 initMultipartUpload 失败：直接抛错返回，不开启事务")
    void blockCheck_s3InitFailure_throwsDirectly() {
        FileNode node = buildFileNode(11L);
        when(fileService.getNodeByIdAndOwner(11L)).thenReturn(node);
        doThrow(new RuntimeException("S3 init failed"))
                .when(storageService).initMultipartUpload(any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> syncBlockService.blockCheck(buildRequest(11L)));
        assertEquals("S3 init failed", ex.getMessage());
        // S3 失败时不开启事务、不执行后续 DB 块布局查询
        verify(fileBlockMapper, never()).selectList(any());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "S3 失败路径同样不应开启 DB 事务");
    }
}
