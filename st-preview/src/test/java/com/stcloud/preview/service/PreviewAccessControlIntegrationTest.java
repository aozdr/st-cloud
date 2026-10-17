package com.stcloud.preview.service;

import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.StorageService;
import com.stcloud.preview.AbstractPreviewIntegrationTest;
import com.stcloud.preview.dto.PreviewResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预览接口资源级鉴权回归测试（P0-1）。
 *
 * <p>修复前 {@code getFileNode} 只校验"节点存在且状态正常"，任意登录用户可用别人的
 * nodeId 读取文本内容或拿到预签名 URL。以下用例覆盖三条边界：</p>
 * <ul>
 *   <li>个人节点：只有 owner 可预览；</li>
 *   <li>团队节点：交给 {@link TeamFileAccessPolicy} 即时核权，拒绝时不读对象存储；</li>
 *   <li>团队节点：策略放行时正常预览（确认修复没有误伤合法场景）。</li>
 * </ul>
 */
class PreviewAccessControlIntegrationTest extends AbstractPreviewIntegrationTest {

    @Autowired
    private StorageService storageService;

    @MockBean
    private TeamFileAccessPolicy teamFileAccessPolicy;

    @Test
    @DisplayName("文件所有者预览自己的文件：正常返回")
    void previewOwnFile_allowed() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "mine.md", "files/mine.md", 0);
        when(storageService.downloadObject("files/mine.md"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("text", vo.getType());
        assertEquals("hello", vo.getContent());
    }

    @Test
    @DisplayName("他人个人文件：拒绝且不读取对象存储")
    void previewOthersPersonalFile_forbidden() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "secret.md", "files/secret.md", 0);

        setUpUser(200L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.preview(node.getId()));

        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        // 唯一路径断言：拒绝发生在读取对象存储之前
        verify(storageService, never()).downloadObject("files/secret.md");
    }

    @Test
    @DisplayName("团队节点且访问策略拒绝：拒绝且不读取对象存储")
    void previewTeamNode_policyDenied() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, 9L, "team-denied.md", "files/team-denied.md", 0);
        when(teamFileAccessPolicy.canView(1L, 100L, 9L, node.getId())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.preview(node.getId()));

        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        verify(storageService, never()).downloadObject("files/team-denied.md");
    }

    @Test
    @DisplayName("团队节点且访问策略放行：正常返回")
    void previewTeamNode_policyAllowed() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, 9L, "team-allowed.md", "files/team-allowed.md", 0);
        when(teamFileAccessPolicy.canView(1L, 100L, 9L, node.getId())).thenReturn(true);
        when(storageService.downloadObject("files/team-allowed.md"))
                .thenReturn(new ByteArrayInputStream("team".getBytes(StandardCharsets.UTF_8)));

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("text", vo.getType());
        assertEquals("team", vo.getContent());
    }
}
