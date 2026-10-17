package com.stcloud.share;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.dto.ShareVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享子树归属判定回归测试（P0-2）。
 *
 * <p>修复前 {@code isWithinShare} 只比较 path 字符串前缀。path 由"父路径 + 名字"拼接，
 * 不同用户在同租户下可以拥有完全相同的 path（例如各自根目录下的 /Documents），
 * 于是攻击者可以：</p>
 * <ol>
 *   <li>用受害者的只读分享拿到子节点 id（这一步本来合法）；</li>
 *   <li>在自己的根目录建同名目录并创建"可下载"分享；</li>
 *   <li>用自己的 shareCode + 受害者的 nodeId 调下载/列目录接口，绕过 allow_download、
 *       下载限次，甚至绕过受害者分享的取消/过期。</li>
 * </ol>
 *
 * <p>修复后按 parentId 祖先链 + 归属范围（同租户、团队看 space、个人看 owner）判定，
 * 他人节点即使 path 相同也不会被认作分享子树。</p>
 */
class ShareSubtreeOwnershipIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long TENANT_ID = 1L;
    private static final Long VICTIM_ID = 1001L;
    private static final Long ATTACKER_ID = 2002L;

    @Autowired
    private FileService fileService;

    @Autowired
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        setUpUser(ATTACKER_ID, TENANT_ID);
    }

    @BeforeEach
    void resetMocks() {
        // 单例 mock 跨测试类共享，使用前重置，避免 stub/调用记录泄漏
        Mockito.reset(fileService, storageService);
    }

    @Test
    @DisplayName("同名目录：不能用自己的分享下载他人子文件（修复前会返回预签名 URL）")
    void attackerShareCannotDownloadVictimFile() {
        // 受害者：/Documents/report.txt
        FileNode victimFolder = insertScopedFolder(VICTIM_ID, 0L, "/Documents");
        FileNode victimFile = insertScopedFile(VICTIM_ID, victimFolder.getId(),
                "/Documents/report.txt", "files/victim/report.txt");
        // 攻击者：自己的 /Documents + 允许下载的分享（path 与受害者根完全相同）
        FileNode attackerFolder = insertScopedFolder(ATTACKER_ID, 0L, "/Documents");
        ShareVO share = createDownloadableShare(attackerFolder.getId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.getDownloadUrl(share.getShareCode(), victimFile.getId(), null, null, null));

        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
        verify(storageService, never()).generateDownloadUrl(anyString());
    }

    @Test
    @DisplayName("同名目录：不能用自己的分享列举他人目录内容")
    void attackerShareCannotListVictimFolder() {
        FileNode victimFolder = insertScopedFolder(VICTIM_ID, 0L, "/Documents");
        insertScopedFile(VICTIM_ID, victimFolder.getId(), "/Documents/secret.txt", "files/victim/secret.txt");
        FileNode attackerFolder = insertScopedFolder(ATTACKER_ID, 0L, "/Documents");
        ShareVO share = createDownloadableShare(attackerFolder.getId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.listShareFiles(share.getShareCode(), victimFolder.getId(), null, null, null));

        assertEquals(ResultCode.SHARE_ACCESS_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("自己的真实子节点仍可下载（修复不误伤合法分享）")
    void ownSubtreeStillDownloadable() {
        FileNode folder = insertScopedFolder(ATTACKER_ID, 0L, "/Documents");
        FileNode child = insertScopedFile(ATTACKER_ID, folder.getId(),
                "/Documents/mine.txt", "files/attacker/mine.txt");
        ShareVO share = createDownloadableShare(folder.getId());
        when(storageService.generateDownloadUrl("files/attacker/mine.txt"))
                .thenReturn("https://s3.example.test/presigned");

        Result<String> result = shareService.getDownloadUrl(share.getShareCode(), child.getId(), null, null, null);

        assertEquals("https://s3.example.test/presigned", result.getData());
        verify(storageService).generateDownloadUrl("files/attacker/mine.txt");
    }

    // ==================== helpers ====================

    private ShareVO createDownloadableShare(Long fileNodeId) {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(fileNodeId);
        req.setShareType(0);
        // 单值 1 = 查看 + 下载，用于验证修复前"可下载分享 + 他人 nodeId"的越权路径
        req.setPermission(1);
        return shareService.createShare(req).getData();
    }

    private FileNode insertScopedFolder(Long ownerId, Long parentId, String path) {
        FileNode folder = new FileNode();
        folder.setTenantId(TENANT_ID);
        folder.setParentId(parentId);
        folder.setNodeType(0);
        folder.setName(path.substring(path.lastIndexOf('/') + 1));
        folder.setPath(path);
        folder.setFileSize(0L);
        folder.setStatus(0);
        folder.setUploadStatus(2);
        folder.setUploaderId(ownerId);
        folder.setOwnerId(ownerId);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        return folder;
    }

    private FileNode insertScopedFile(Long ownerId, Long parentId, String path, String storagePath) {
        FileNode file = new FileNode();
        file.setTenantId(TENANT_ID);
        file.setParentId(parentId);
        file.setNodeType(1);
        file.setName(path.substring(path.lastIndexOf('/') + 1));
        file.setPath(path);
        file.setFileSize(2L);
        file.setFileMd5("md5-" + file.getName());
        file.setContentType("text/plain");
        file.setSuffix("txt");
        file.setStoragePath(storagePath);
        file.setStatus(0);
        file.setUploadStatus(2);
        file.setUploaderId(ownerId);
        file.setOwnerId(ownerId);
        file.setVersion(0);
        fileNodeMapper.insert(file);
        return file;
    }
}
