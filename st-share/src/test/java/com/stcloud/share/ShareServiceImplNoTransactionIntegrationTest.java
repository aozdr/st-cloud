package com.stcloud.share;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.entity.FileShare;
import com.stcloud.share.enums.ShareStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TX-02（F1-1）getDownloadUrl 去事务集成测试。
 * <p>
 * 本类用 {@code @Transactional(propagation = NOT_SUPPORTED)} 挂起测试外层事务，
 * 再通过 {@link TransactionSynchronizationManager#isActualTransactionActive()}
 * 断言 getDownloadUrl 自身不再开启 DB 事务（与 EventOutboxIntegrationTest 同款断言方式）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShareServiceImplNoTransactionIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long USER_ID = 3001L;
    private static final Long TENANT_ID = 1L;

    @Autowired
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, TENANT_ID);
        // storageService 为 Spring 单例 mock（跨测试类共享），本类使用前重置，避免 stub 泄漏影响其它用例
        Mockito.reset(storageService);
    }

    /** 构造一个可下载分享记录（走真实 Mapper + 租户拦截器/自动填充） */
    private FileShare insertDownloadableShare(Long fileNodeId) {
        FileShare share = new FileShare();
        share.setShareCode("TXN2");
        share.setFileNodeId(fileNodeId);
        share.setCreatorId(USER_ID);
        share.setShareType(0);
        share.setPermission(1);
        share.setPermissions("{\"view\":true,\"download\":true}");
        share.setAllowDownload(1);
        share.setDownloadLimit(null);
        share.setDownloadCount(0);
        share.setViewCount(0);
        share.setStatus(ShareStatus.ACTIVE.getCode());
        fileShareMapper.insert(share);
        return share;
    }

    @Test
    @DisplayName("F1-1 getDownloadUrl 成功生成 URL 且不开启 DB 事务")
    void getDownloadUrl_doesNotOpenTransaction() {
        when(storageService.generateDownloadUrl(any()))
                .thenReturn("https://s3.example.test/presigned-no-tx");
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "no-tx.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());

        // 测试自身不在事务内，方法调用期间/之后均不得出现实际事务
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "调用前不应存在事务（测试外层事务已挂起）");

        var result = shareService.getDownloadUrl(share.getShareCode(), null, null, null, null);
        assertEquals("https://s3.example.test/presigned-no-tx", result.getData());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "getDownloadUrl 不应开启 DB 事务");

        // 无事务时下载计数 UPDATE 独立自动提交，可重复调用
        var second = shareService.getDownloadUrl(share.getShareCode(), null, null, null, null);
        assertEquals("https://s3.example.test/presigned-no-tx", second.getData());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "重复调用仍不应开启 DB 事务");
        assertEquals(2, fileShareMapper.selectById(share.getId()).getDownloadCount());
    }
}
