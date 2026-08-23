package com.stcloud.share;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.dto.ShareVO;
import com.stcloud.share.dto.UpdateShareRequest;
import com.stcloud.share.entity.FileShare;
import com.stcloud.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享权限上限集成测试（权限模型重设计，TASK-PERM-BE2，H2 真实 Mapper + Mock 外部服务）。
 * <p>
 * 覆盖：个人文件默认分享 {view,download}；分享权限 ⊆ 用户有效权限（超权拒绝）；
 * 团队分享 share 权限点前置；allow_download 与 permissions 联动；下载/流式按权限集含 download 双保险。
 */
class ShareServiceImplPermissionLimitIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;
    private static final Long SPACE_ID = 6001L;

    @Autowired
    private TeamService teamService;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, TENANT_ID);
        // teamService 为 Spring 单例 mock（跨测试类共享），本类使用前重置，避免 stub 泄漏影响其它用例
        Mockito.reset(teamService);
    }

    private ShareVO createShare(FileNode fileNode, String permissionsJson) {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(fileNode.getId());
        req.setShareType(0);
        req.setPermissions(permissionsJson);
        return shareService.createShare(req).getData();
    }

    @Test
    @DisplayName("权限上限：个人文件未传权限时默认分享 {view,download}，allow_download=1")
    void personalFileDefaultPermissionsViewAndDownload() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "default-perm.txt", 0);
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(file.getId());
        req.setShareType(0);
        // 不传 permissions/permission → 默认 {view,download}
        ShareVO vo = shareService.createShare(req).getData();

        FileShare saved = fileShareMapper.selectById(vo.getId());
        assertNotNull(saved.getPermissions());
        assertTrue(saved.getPermissions().contains("view"));
        assertTrue(saved.getPermissions().contains("download"));
        assertEquals(1, saved.getAllowDownload());
        // 兼容旧单值字段推导：含 download → 1
        assertEquals(1, saved.getPermission());
    }

    @Test
    @DisplayName("权限上限：团队文件无 download 权限时请求含 download 被拒 SHARE_ACCESS_DENIED")
    void teamFileOverPermissionRejected() {
        FileNode team = insertFileNode(TENANT_ID, USER_ID, "team-over.txt", 0, SPACE_ID);
        when(teamService.resolveMyPermissions(eq(SPACE_ID), eq(team.getId())))
                .thenReturn(new HashSet<>(Arrays.asList("view", "share")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createShare(team, "{\"view\":true,\"download\":true}"));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("分享权限不能超过你的权限"));
    }

    @Test
    @DisplayName("权限上限：个人文件分享权限上限 {view,download}，upload 超权被拒（SP1 口径统一）")
    void personalFileOverPermissionRejected() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "personal-over.txt", 0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> createShare(file, "{\"view\":true,\"upload\":true}"));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("分享权限不能超过你的权限"));
    }

    @Test
    @DisplayName("权限上限：团队分享走 requirePermissions(spaceId, nodeId, share) 前置")
    void teamShareRequiresSharePermissionPoint() {
        FileNode team = insertFileNode(TENANT_ID, USER_ID, "team-share.txt", 0, SPACE_ID);
        when(teamService.resolveMyPermissions(eq(SPACE_ID), eq(team.getId())))
                .thenReturn(new HashSet<>(Arrays.asList("view", "share")));

        ShareVO vo = createShare(team, "{\"view\":true}");
        assertNotNull(vo);
        verify(teamService).requirePermissions(eq(SPACE_ID), eq(team.getId()), eq("share"));
        verify(teamService).resolveMyPermissions(eq(SPACE_ID), eq(team.getId()));
    }

    @Test
    @DisplayName("权限上限：团队文件缺少 share 权限点被拒 TEAM_PERMISSION_DENIED")
    void teamShareWithoutSharePointRejected() {
        FileNode team = insertFileNode(TENANT_ID, USER_ID, "team-noshare.txt", 0, SPACE_ID);
        Mockito.doThrow(new BusinessException(ResultCode.TEAM_PERMISSION_DENIED.getCode(), "权限不足"))
                .when(teamService).requirePermissions(eq(SPACE_ID), eq(team.getId()), eq("share"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createShare(team, "{\"view\":true}"));
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("权限上限：团队文件未传权限时默认=用户有效权限（{view,upload} → allow_download=0）")
    void teamFileDefaultPermissionsFromEffectivePerms() {
        FileNode team = insertFileNode(TENANT_ID, USER_ID, "team-default.txt", 0, SPACE_ID);
        when(teamService.resolveMyPermissions(eq(SPACE_ID), eq(team.getId())))
                .thenReturn(new HashSet<>(Arrays.asList("view", "upload", "share")));

        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(team.getId());
        req.setShareType(0);
        ShareVO vo = shareService.createShare(req).getData();

        FileShare saved = fileShareMapper.selectById(vo.getId());
        assertTrue(saved.getPermissions().contains("view"));
        assertTrue(saved.getPermissions().contains("upload"));
        // 有效权限不含 download → allow_download=0
        assertEquals(0, saved.getAllowDownload());
    }

    @Test
    @DisplayName("权限上限：allow_download 与 permissions 联动（含 download→1，取交集）")
    void allowDownloadLinkedWithPermissions() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "link.txt", 0);

        // 权限集仅 view → allow_download=0
        ShareVO viewOnly = createShare(file, "{\"view\":true}");
        assertEquals(0, fileShareMapper.selectById(viewOnly.getId()).getAllowDownload());

        // 权限集 view+download 且显式 allowDownload=false → 取交集 = 0
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(file.getId());
        req.setShareType(0);
        req.setPermissions("{\"view\":true,\"download\":true}");
        req.setAllowDownload(0);
        ShareVO forcedOff = shareService.createShare(req).getData();
        assertEquals(0, fileShareMapper.selectById(forcedOff.getId()).getAllowDownload());

        // 权限集 view+download 未显式 allowDownload → 1
        ShareVO download = createShare(file, "{\"view\":true,\"download\":true}");
        assertEquals(1, fileShareMapper.selectById(download.getId()).getAllowDownload());
    }

    @Test
    @DisplayName("权限上限：updateShare permissions 更新联动 allow_download")
    void updateSharePermissionsLinkedWithAllowDownload() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "update-link.txt", 0);
        ShareVO vo = createShare(file, "{\"view\":true,\"download\":true}");
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getAllowDownload());

        // 去掉 download → allow_download 联动为 0
        UpdateShareRequest req = new UpdateShareRequest();
        req.setPermissions("{\"view\":true}");
        shareService.updateShare(vo.getId(), req);
        FileShare after = fileShareMapper.selectById(vo.getId());
        assertTrue(after.getPermissions().contains("view"));
        assertTrue(!after.getPermissions().contains("download"));
        assertEquals(0, after.getAllowDownload());

        // 加回 download → allow_download 联动为 1
        UpdateShareRequest req2 = new UpdateShareRequest();
        req2.setPermissions("{\"view\":true,\"download\":true}");
        shareService.updateShare(vo.getId(), req2);
        assertEquals(1, fileShareMapper.selectById(vo.getId()).getAllowDownload());
    }

    @Test
    @DisplayName("权限上限：权限集不含 download 时 getDownloadUrl 被拒（双保险）")
    void getDownloadUrlRejectedWhenPermissionsLackDownload() {
        FileNode file = insertFileNode(TENANT_ID, USER_ID, "no-dl.txt", 0);
        // 个人文件权限上限 {view,download}：仅 view（不含 download）即可验证下载链路拒绝
        ShareVO vo = createShare(file, "{\"view\":true}");
        assertEquals(0, fileShareMapper.selectById(vo.getId()).getAllowDownload());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(vo.getShareCode(), null, null, null, null));
        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("该分享不可下载"));
    }
}
