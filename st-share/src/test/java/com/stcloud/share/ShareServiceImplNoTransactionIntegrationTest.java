package com.stcloud.share;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.StorageService;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.share.entity.FileShare;
import com.stcloud.share.enums.ShareStatus;
import com.stcloud.share.mapper.FileShareMapper;
import com.stcloud.share.service.impl.ShareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.AdditionalAnswers.delegatesTo;

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
        share.setShareCode("TXN2" + fileNodeId);
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

    @Test
    @DisplayName("TC05-01 H2 五个流在源对象打开后争抢最后一次下载额度")
    void concurrentStreamsOnlyOneMayOutput() throws Exception {
        byte[] bytes = "isolated-share-content".getBytes(StandardCharsets.UTF_8);
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "permit-race.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId()).set(FileShare::getDownloadLimit, 1));
        CyclicBarrier atSourceOpen = new CyclicBarrier(5);
        when(storageService.downloadObject(any())).thenAnswer(ignored -> {
            // 所有请求均完成分享/文件/权限校验，再同时争抢数据库许可。
            atSourceOpen.await(5, TimeUnit.SECONDS);
            return new ByteArrayInputStream(bytes);
        });
        ExecutorService workers = Executors.newFixedThreadPool(5);
        try {
            List<Future<byte[]>> responses = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                responses.add(workers.submit(() -> {
                    setUpUser(USER_ID, TENANT_ID);
                    try {
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        try {
                            shareService.streamShareFile(share.getShareCode(), null, null,
                                    null, null, response);
                        } catch (BusinessException denied) {
                            assertEquals(0, response.getContentAsByteArray().length);
                        }
                        return response.getContentAsByteArray();
                    } finally {
                        UserContext.clear();
                        TenantContext.clear();
                    }
                }));
            }
            int successful = 0;
            for (Future<byte[]> response : responses) {
                byte[] body = response.get(10, TimeUnit.SECONDS);
                if (body.length > 0) {
                    successful++;
                    assertEquals(new String(bytes, StandardCharsets.UTF_8),
                            new String(body, StandardCharsets.UTF_8));
                }
            }
            assertEquals(1, successful);
            assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("TC05-02 H2 预签名 URL 与四个流共用最后一次额度")
    void urlAndStreamsCompeteForOnePermit() throws Exception {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "mixed-permit-race.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId()).set(FileShare::getDownloadLimit, 1));
        CyclicBarrier readyForPermit = new CyclicBarrier(5);
        when(storageService.generateDownloadUrl(any())).thenAnswer(ignored -> {
            readyForPermit.await(5, TimeUnit.SECONDS);
            return "https://isolated.example.test/only-if-granted";
        });
        when(storageService.downloadObject(any())).thenAnswer(ignored -> {
            readyForPermit.await(5, TimeUnit.SECONDS);
            return new ByteArrayInputStream("file-bytes".getBytes(StandardCharsets.UTF_8));
        });
        ExecutorService workers = Executors.newFixedThreadPool(5);
        try {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final boolean urlRequest = i == 0;
                requests.add(workers.submit(() -> {
                    setUpUser(USER_ID, TENANT_ID);
                    try {
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        try {
                            if (urlRequest) {
                                return shareService.getDownloadUrl(share.getShareCode(), null,
                                        null, null, null).getData() != null;
                            }
                            shareService.streamShareFile(share.getShareCode(), null,
                                    null, null, null, response);
                            return response.getContentAsByteArray().length > 0;
                        } catch (BusinessException denied) {
                            assertEquals(0, response.getContentAsByteArray().length);
                            return false;
                        }
                    } finally {
                        UserContext.clear();
                        TenantContext.clear();
                    }
                }));
            }
            int successful = 0;
            for (Future<Boolean> request : requests) {
                if (request.get(10, TimeUnit.SECONDS)) successful++;
            }
            assertEquals(1, successful);
            assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("TC05-05 源流或 URL 签名失败均不占下载次数")
    void externalSourceFailuresDoNotConsumePermit() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "source-failure.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        when(storageService.downloadObject(any())).thenThrow(new IllegalStateException("source unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> shareService.streamShareFile(
                share.getShareCode(), null, null, null, null, response));
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());

        when(storageService.generateDownloadUrl(any())).thenThrow(new IllegalStateException("signer unavailable"));
        assertThrows(IllegalStateException.class, () -> shareService.getDownloadUrl(
                share.getShareCode(), null, null, null, null));
        assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());
    }

    @Test
    @DisplayName("TC05-06 源流打开后另一 URL 用尽额度，当前流关闭且不输出")
    void exhaustedAfterSourceOpenClosesStreamBeforeOutput() throws Exception {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "permit-after-open.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId()).set(FileShare::getDownloadLimit, 1));
        AtomicBoolean closed = new AtomicBoolean();
        when(storageService.generateDownloadUrl(any())).thenReturn("https://isolated.example.test/permit");
        when(storageService.downloadObject(any())).thenAnswer(ignored -> {
            // 外部源流已可打开；另一个请求先完成 URL 授予，再交还当前流去占位。
            assertEquals("https://isolated.example.test/permit",
                    shareService.getDownloadUrl(share.getShareCode(), null, null, null, null).getData());
            return new FilterInputStream(new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8))) {
                @Override public void close() throws IOException { closed.set(true); super.close(); }
            };
        });
        MockHttpServletResponse response = spy(new MockHttpServletResponse());
        assertThrows(BusinessException.class, () -> shareService.streamShareFile(
                share.getShareCode(), null, null, null, null, response));
        assertTrue(closed.get());
        verify(response, never()).getOutputStream();
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals(null, response.getHeader("Content-Length"));
        assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount());
    }

    @Test
    @DisplayName("TC05-07 授予后首字节前及传输中断均不退次数并关闭源流")
    void interruptedStreamKeepsGrantedPermit() {
        for (int readableBytes : new int[]{0, 3}) {
            FileNode file = insertFileNode(TENANT_ID, USER_ID, "interrupted-" + readableBytes + ".txt", 0);
            FileShare share = insertDownloadableShare(file.getId());
            AtomicBoolean closed = new AtomicBoolean();
            when(storageService.downloadObject(any())).thenAnswer(ignored -> new InputStream() {
                private int readCount;
                @Override public int read() throws IOException {
                    if (readCount++ >= readableBytes) throw new IOException("client/source disconnected");
                    return 'x';
                }
                @Override public void close() { closed.set(true); }
            });
            MockHttpServletResponse response = new MockHttpServletResponse();
            shareService.streamShareFile(share.getShareCode(), null, null, null, null, response);
            assertTrue(closed.get(), "故障后必须关闭源流");
            assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount(),
                    "授予后失败不能退还额度");
            assertEquals(readableBytes, response.getContentAsByteArray().length);
            assertFalse(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8).contains("{\"code\""),
                    "文件响应不能追加 JSON 错误体");
        }
    }

    @Test
    @DisplayName("TC05-09 无限额度下 URL 和流每次各原子计数一次")
    void unlimitedShareCountsEveryGrantOnce() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "unlimited.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
        when(storageService.generateDownloadUrl(any())).thenReturn("https://isolated.example.test/file");
        when(storageService.downloadObject(any())).thenAnswer(ignored -> new ByteArrayInputStream(bytes));
        for (int i = 0; i < 3; i++) {
            assertEquals("https://isolated.example.test/file",
                    shareService.getDownloadUrl(share.getShareCode(), null, null, null, null).getData());
            MockHttpServletResponse response = new MockHttpServletResponse();
            shareService.streamShareFile(share.getShareCode(), null, null, null, null, response);
            assertEquals("ok", new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
            assertEquals((i + 1) * 2, fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
    }

    @Test
    @DisplayName("TC05-11 初检后分享禁用、关闭下载或过期，最终授予全部拒绝")
    void changesBeforePermitDenyOutput() {
        for (int variant = 0; variant < 3; variant++) {
            FileNode file = insertFileNode(TENANT_ID, USER_ID, "revoked-" + variant + ".txt", 0);
            FileShare share = insertDownloadableShare(file.getId());
            int selected = variant;
            AtomicBoolean closed = new AtomicBoolean();
            when(storageService.downloadObject(any())).thenAnswer(ignored -> {
                // 初次鉴权已通过，外部源流打开时改变分享状态，最终条件 UPDATE 必须重新判定。
                LambdaUpdateWrapper<FileShare> change = new LambdaUpdateWrapper<FileShare>()
                        .eq(FileShare::getId, share.getId());
                if (selected == 0) change.set(FileShare::getStatus, ShareStatus.CANCELLED.getCode());
                else if (selected == 1) change.set(FileShare::getAllowDownload, 0);
                else change.set(FileShare::getExpireAt,
                        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
                fileShareMapper.update(null, change);
                return new FilterInputStream(new ByteArrayInputStream(new byte[]{1})) {
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            });
            MockHttpServletResponse response = spy(new MockHttpServletResponse());
            assertThrows(BusinessException.class, () -> shareService.streamShareFile(
                    share.getShareCode(), null, null, null, null, response));
            assertTrue(closed.get());
            verify(response, never()).getOutputStream();
            assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
    }

    @Test
    @DisplayName("TC05-03 禁用、过期、关闭下载及缺权限在访问前拒绝且不计次")
    void invalidSharePreconditionsDoNotConsumePermit() {
        for (int variant = 0; variant < 4; variant++) {
            FileNode file = insertFileNode(TENANT_ID, USER_ID, "precondition-" + variant + ".txt", 0);
            FileShare share = insertDownloadableShare(file.getId());
            LambdaUpdateWrapper<FileShare> change = new LambdaUpdateWrapper<FileShare>()
                    .eq(FileShare::getId, share.getId());
            if (variant == 0) change.set(FileShare::getStatus, ShareStatus.CANCELLED.getCode());
            else if (variant == 1) change.set(FileShare::getExpireAt,
                    LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
            else if (variant == 2) change.set(FileShare::getAllowDownload, 0);
            else change.set(FileShare::getPermissions, "{\"view\":true}");
            fileShareMapper.update(null, change);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThrows(BusinessException.class, () -> shareService.streamShareFile(
                    share.getShareCode(), null, null, null, null, response));
            assertEquals(0, response.getContentAsByteArray().length);
            assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
        verify(storageService, never()).downloadObject(any());
    }

    @Test
    @DisplayName("TC05-04 缺失、回收站、未上传完及子树外节点均不授予")
    void invalidTargetNodesDoNotConsumePermit() {
        FileNode root = insertFileNode(TENANT_ID, USER_ID, "root.txt", 0);
        FileShare share = insertDownloadableShare(root.getId());
        FileNode trashed = insertFileNode(TENANT_ID, USER_ID, "trashed.txt", 1);
        FileNode incomplete = insertFileNode(TENANT_ID, USER_ID, "incomplete.txt", 0);
        incomplete.setUploadStatus(1);
        fileNodeMapper.updateById(incomplete);
        FileNode outside = insertFileNode(TENANT_ID, USER_ID, "outside.txt", 0);
        for (Long targetId : new Long[]{Long.MAX_VALUE, trashed.getId(), incomplete.getId(), outside.getId()}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThrows(BusinessException.class, () -> shareService.streamShareFile(
                    share.getShareCode(), targetId, null, null, null, response));
            assertEquals(0, response.getContentAsByteArray().length);
            assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());
        }
        verify(storageService, never()).downloadObject(any());
    }

    @Test
    @DisplayName("TC05-10 不支持的缩略图失败不占额度，随后原文件流仅占一次")
    void unsupportedThumbnailDoesNotBypassOriginalStreamPermit() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "not-image.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        when(storageService.downloadObject(any()))
                .thenAnswer(ignored -> new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse thumbnailResponse = new MockHttpServletResponse();
        assertThrows(BusinessException.class, () -> shareService.streamShareThumbnail(
                share.getShareCode(), null, "sm", null, null, null, thumbnailResponse));
        assertEquals(0, thumbnailResponse.getContentAsByteArray().length);
        assertEquals(0, fileShareMapper.selectById(share.getId()).getDownloadCount());
        MockHttpServletResponse originalResponse = new MockHttpServletResponse();
        shareService.streamShareFile(share.getShareCode(), null, null, null, null, originalResponse);
        assertEquals("original", new String(originalResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount());
    }

    @Test
    @DisplayName("TC05-13 URL 应用层重试仍须重新争剩余额度")
    void retryingPresignedUrlRequiresAnotherPermit() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "url-retry.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId()).set(FileShare::getDownloadLimit, 1));
        when(storageService.generateDownloadUrl(any())).thenReturn("https://isolated.example.test/granted");
        assertEquals("https://isolated.example.test/granted",
                shareService.getDownloadUrl(share.getShareCode(), null, null, null, null).getData());
        assertThrows(BusinessException.class, () -> shareService.getDownloadUrl(
                share.getShareCode(), null, null, null, null));
        assertEquals(1, fileShareMapper.selectById(share.getId()).getDownloadCount());
    }

    @Test
    @DisplayName("TC05-08 数据库授予故障不能输出，响应丢失后的重试须重新争额度")
    void permitDatabaseFailureAndLostResponseRetry() throws Exception {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "permit-db-fault.txt", 0);
        FileShare share = insertDownloadableShare(file.getId());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId()).set(FileShare::getDownloadLimit, 1));
        AtomicBoolean sourceClosed = new AtomicBoolean();
        when(storageService.downloadObject(any())).thenAnswer(ignored ->
                new FilterInputStream(new ByteArrayInputStream(new byte[]{1, 2})) {
                    @Override public void close() throws IOException { sourceClosed.set(true); super.close(); }
                });
        ShareServiceImpl target = AopTestUtils.getTargetObject(shareService);
        FileShareMapper original = (FileShareMapper) ReflectionTestUtils.getField(target, "fileShareMapper");
        FileShareMapper failing = Mockito.mock(FileShareMapper.class, delegatesTo(original));
        doThrow(new IllegalStateException("injected permit database outage"))
                .when(failing).update(isNull(), any());
        MockHttpServletResponse response = spy(new MockHttpServletResponse());
        try {
            ReflectionTestUtils.setField(target, "fileShareMapper", failing);
            assertThrows(IllegalStateException.class, () -> shareService.streamShareFile(
                    share.getShareCode(), null, null, null, null, response));
        } finally {
            ReflectionTestUtils.setField(target, "fileShareMapper", original);
        }
        assertTrue(sourceClosed.get());
        verify(response, never()).getOutputStream();
        assertEquals(0, original.selectById(share.getId()).getDownloadCount());

        when(storageService.generateDownloadUrl(any())).thenReturn("https://isolated.example.test/first-grant");
        // 模拟许可已提交、返回 URL 在网络上丢失：客户端不知道第一次成功，重试必须重新申请。
        shareService.getDownloadUrl(share.getShareCode(), null, null, null, null);
        assertThrows(BusinessException.class, () -> shareService.getDownloadUrl(
                share.getShareCode(), null, null, null, null));
        assertEquals(1, original.selectById(share.getId()).getDownloadCount());
    }
}
