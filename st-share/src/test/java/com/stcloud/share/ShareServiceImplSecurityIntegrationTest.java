package com.stcloud.share;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.team.service.TeamService;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.dto.ShareVO;
import com.stcloud.share.dto.UpdateShareRequest;
import com.stcloud.share.entity.FileShare;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享安全修复集成测试（S-01/S-02/S-03，H2 真实 Mapper + Mock 外部服务）。
 * <p>
 * 覆盖：分享越权拦截（个人/团队）、匿名下载 URL 不再 NPE、仅查看不可下载、
 * streamShareFile 下载次数限制与计数、三处子树 path 边界（同名前缀越权）。
 */
class ShareServiceImplSecurityIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 2002L;
    private static final Long TENANT_ID = 1L;
    private static final Long SPACE_ID = 5001L;

    @Autowired
    private FileService fileService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private TeamService teamService;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, TENANT_ID);
    }

    @BeforeEach
    void resetMocks() {
        // fileService/storageService/teamService 为 Spring 单例 mock（跨测试类共享），本类使用前重置，避免 stub 泄漏影响其它用例
        Mockito.reset(fileService, storageService, teamService);
    }

    private ShareVO createShare(Long fileNodeId, Integer permission, Integer downloadLimit) {
        return createShare(fileNodeId, permission, downloadLimit, null);
    }

    private ShareVO createShare(Long fileNodeId, Integer permission, Integer downloadLimit, Integer allowDownload) {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(fileNodeId);
        req.setShareType(0);
        req.setPermission(permission != null ? permission : 0);
        req.setAllowDownload(allowDownload);
        req.setDownloadLimit(downloadLimit);
        return shareService.createShare(req).getData();
    }

    // ==================== S-01 创建分享归属校验 ====================

    @Test
    @DisplayName("S-01 分享自己文件成功")
    void shareOwnFileSuccess() {
        FileNode own = insertFileNode(TENANT_ID, USER_ID, "own.txt", 0);
        ShareVO vo = createShare(own.getId(), 1, null);
        assertNotNull(vo);
        assertEquals(own.getId(), vo.getFileNodeId());
    }

    @Test
    @DisplayName("S-01 分享他人个人文件被拒：SHARE_ACCESS_DENIED")
    void shareOtherPersonalFileRejected() {
        FileNode other = insertFileNode(TENANT_ID, OTHER_USER_ID, "other.txt", 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> createShare(other.getId(), 1, null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("无权分享他人文件"));
    }

    @Test
    @DisplayName("S-01 团队文件无 share 权限被拒：requirePermissions 抛 TEAM_PERMISSION_DENIED")
    void shareTeamFileNonMemberRejected() {
        FileNode team = insertFileNode(TENANT_ID, OTHER_USER_ID, "team.txt", 0, SPACE_ID);
        doThrow(new BusinessException(ResultCode.TEAM_PERMISSION_DENIED.getCode(), "您不是该空间的成员"))
                .when(teamService).requirePermissions(eq(SPACE_ID), eq(team.getId()), eq("share"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createShare(team.getId(), 1, null));
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S-01 团队文件权限链通过：requirePermissions(spaceId, nodeId, share) + resolveMyPermissions")
    void shareTeamFileMemberAllowed() {
        FileNode team = insertFileNode(TENANT_ID, USER_ID, "team-ok.txt", 0, SPACE_ID);
        when(teamService.resolveMyPermissions(eq(SPACE_ID), eq(team.getId())))
                .thenReturn(new HashSet<>(Arrays.asList("view", "download", "share")));
        ShareVO vo = createShare(team.getId(), 1, null);
        assertNotNull(vo);
        verify(teamService).requirePermissions(eq(SPACE_ID), eq(team.getId()), eq("share"));
        verify(teamService).resolveMyPermissions(eq(SPACE_ID), eq(team.getId()));
    }

    // ==================== S-02 分享下载链路 ====================

    @Test
    @DisplayName("S-02 匿名访问分享下载 URL 成功（不再 NPE）")
    void anonymousDownloadUrlNoNpe() {
        when(storageService.generateDownloadUrl(any()))
                .thenReturn("https://s3.example.test/presigned");
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "dl.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null);

        // 模拟匿名访问：清空登录上下文（无 userId），分享链路不得再走 owner 校验
        UserContext.clear();
        Result<String> result = shareService.getDownloadUrl(vo.getShareCode(), null, null);
        assertEquals("https://s3.example.test/presigned", result.getData());
    }

    @Test
    @DisplayName("S-02 仅查看（permission=0）分享 getDownloadUrl 被拒")
    void viewOnlyShareDownloadRejected() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "view.txt", 0);
        ShareVO vo = createShare(file.getId(), 0, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(vo.getShareCode(), null, null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        // permission=0 未显式传 allowDownload 时默认 0，统一命中 allow_download 下载开关
        assertTrue(ex.getMessage().contains("该分享不可下载"));
    }

    @Test
    @DisplayName("S-02 创建分享 allowDownload 未显式传时与 permission 联动")
    void createShareAllowDownloadDefaultsFromPermission() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "default-flag.txt", 0);
        // permission=0（仅查看）未传 allowDownload → 默认 0（禁止下载）
        ShareVO view = createShare(file.getId(), 0, null);
        assertEquals(0, fileShareMapper.selectById(view.getId()).getAllowDownload());
        // permission=1（可下载）未传 allowDownload → 默认 1（允许下载）
        ShareVO download = createShare(file.getId(), 1, null);
        assertEquals(1, fileShareMapper.selectById(download.getId()).getAllowDownload());
    }

    @Test
    @DisplayName("S-02 updateShare 可切换 allowDownload 下载开关")
    void updateShareTogglesAllowDownload() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "toggle-flag.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null, 1);
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getAllowDownload());

        UpdateShareRequest req = new UpdateShareRequest();
        req.setAllowDownload(0);
        shareService.updateShare(vo.getId(), req);
        assertEquals(0, fileShareMapper.selectById(vo.getId()).getAllowDownload());

        req.setAllowDownload(1);
        shareService.updateShare(vo.getId(), req);
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getAllowDownload());
    }

    @Test
    @DisplayName("S-02 allowDownload=1 时 getDownloadUrl 正常生成")
    void allowDownloadOneDownloadUrlOk() {
        when(storageService.generateDownloadUrl(any()))
                .thenReturn("https://s3.example.test/presigned");
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "flag-on.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null, 1);

        Result<String> result = shareService.getDownloadUrl(vo.getShareCode(), null, null);
        assertEquals("https://s3.example.test/presigned", result.getData());
    }

    @Test
    @DisplayName("S-02 allowDownload=0 时 getDownloadUrl 被拒（permission=1 也拒绝）")
    void allowDownloadZeroDownloadUrlRejected() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "flag-off.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(vo.getShareCode(), null, null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("该分享不可下载"));
    }

    @Test
    @DisplayName("S-02 allowDownload=0 时 streamShareFile 被拒（堵住流式绕过）")
    void allowDownloadZeroStreamRejected() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "flag-off-stream.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null, 0);

        HttpServletResponse response = mock(HttpServletResponse.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.streamShareFile(vo.getShareCode(), null, null, response));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("该分享不可下载"));
    }

    @Test
    @DisplayName("S-02 downloadLimit 达到后 streamShareFile 拒绝")
    void streamShareFileLimitExceededRejected() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "limit.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, 1);
        // 直接置 download_count=1，模拟已达上限
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, vo.getId())
                .set(FileShare::getDownloadCount, 1));

        HttpServletResponse response = mock(HttpServletResponse.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.streamShareFile(vo.getShareCode(), null, null, response));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("下载次数已达上限"));
    }

    @Test
    @DisplayName("S-02 allowDownload=1 流式预览成功并计入下载次数")
    void streamShareFileAllowedCountsDownload() throws Exception {
        when(storageService.downloadObject(any()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "preview.txt", 0);
        // allowDownload=1（显式开启下载开关）：inline 预览允许，并计入下载次数
        ShareVO vo = createShare(file.getId(), 1, 10, 1);

        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        shareService.streamShareFile(vo.getShareCode(), null, null, response);

        FileShare after = fileShareMapper.selectById(vo.getId());
        assertEquals(1, after.getDownloadCount());
    }

    // ==================== S-03 子树 path 边界 ====================

    @Test
    @DisplayName("S-03 getDownloadUrl 同名前缀子文件（/a.txt vs /a.txt2）被拒")
    void getDownloadUrlRejectsSamePrefixSibling() {
        FileNode root = insertFileNode(TENANT_ID, USER_ID, "a.txt", 0);
        FileNode sibling = insertFileNode(TENANT_ID, USER_ID, "a.txt2", 0);
        ShareVO vo = createShare(root.getId(), 1, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(vo.getShareCode(), sibling.getId(), null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S-03 listShareFiles 同名前缀目录（/doc vs /documents）被拒")
    void listShareFilesRejectsSamePrefixSibling() {
        FileNode rootFolder = insertFolder(TENANT_ID, USER_ID, "doc");
        FileNode siblingFolder = insertFolder(TENANT_ID, USER_ID, "documents");
        ShareVO vo = createShare(rootFolder.getId(), 1, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.listShareFiles(vo.getShareCode(), siblingFolder.getId(), null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S-03 streamShareFile 同名前缀子文件（/a.txt vs /a.txt2）被拒")
    void streamShareFileRejectsSamePrefixSibling() {
        FileNode root = insertFileNode(TENANT_ID, USER_ID, "a.txt", 0);
        FileNode sibling = insertFileNode(TENANT_ID, USER_ID, "a.txt2", 0);
        ShareVO vo = createShare(root.getId(), 0, null);

        HttpServletResponse response = mock(HttpServletResponse.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.streamShareFile(vo.getShareCode(), sibling.getId(), null, response));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
    }

    // ==================== S-06 分享码生成 ====================

    @Test
    @DisplayName("S-06 新分享 shareCode 为 4 位且字符集合规（排除 0/O/1/I）")
    void createShareGenerates4CharSafeShareCode() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "code.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null);
        assertNotNull(vo.getShareCode());
        assertEquals(4, vo.getShareCode().length());
        assertTrue(vo.getShareCode().matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}"));
    }

    // ==================== S-07 下载计数原子条件更新 ====================

    @Test
    @DisplayName("S-07 getDownloadUrl 未达上限时原子递增并成功生成 URL")
    void getDownloadUrlIncrementsWithinLimit() {
        when(storageService.generateDownloadUrl(any()))
                .thenReturn("https://s3.example.test/presigned");
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "atomic-ok.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, 1);

        Result<String> result = shareService.getDownloadUrl(vo.getShareCode(), null, null);
        assertEquals("https://s3.example.test/presigned", result.getData());
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getDownloadCount());
    }

    @Test
    @DisplayName("S-07 getDownloadUrl 已达上限时拒绝且计数不再递增")
    void getDownloadUrlAtLimitRejectedWithoutIncrement() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "atomic-limit.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, 1);
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, vo.getId())
                .set(FileShare::getDownloadCount, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(vo.getShareCode(), null, null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("下载次数已达上限"));
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getDownloadCount());
    }

    // ==================== S-09 流式限速 ====================

    @Test
    @DisplayName("S-09 流式分享默认限速 5MB/s：1MB 数据耗时不低于理论下限")
    void streamShareFileRateLimitedTo5MBps() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        when(storageService.downloadObject(any()))
                .thenReturn(new ByteArrayInputStream(payload));
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "rate.txt", 0);
        ShareVO vo = createShare(file.getId(), 1, null, 1);

        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        long start = System.nanoTime();
        shareService.streamShareFile(vo.getShareCode(), null, null, response);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // 1MB @ 5MB/s 理论下限约 200ms；宽松断言 150ms 避免 CI 计时抖动
        assertTrue(elapsedMs >= 150L, "限速未生效，耗时 " + elapsedMs + "ms < 150ms");
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getDownloadCount());
    }
}
