package com.stcloud.core.editor;

import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.editor.dto.EditorConfigResponse;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.VersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 编辑器配置生成单元测试（Mockito）。
 * 重点守卫 PDF 可编辑：owner 打开 PDF 应返回 mode=edit 且 permissions.edit=true；
 * 只读（view）模式仍强制 mode=view / edit=false（对应原 PDF 强拦逻辑移除后不被破坏）。
 */
@ExtendWith(MockitoExtension.class)
class EditorConfigServiceImplTest {

    @Mock
    private EditorProperties editorProperties;
    @Mock
    private EditorPermissionService editorPermissionService;
    @Mock
    private EditorLockService editorLockService;
    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private FileNodeMapper fileNodeMapper;
    @Mock
    private VersionService versionService;

    @InjectMocks
    private EditorConfigServiceImpl service;

    private FileNode pdfNode;

    @BeforeEach
    void setUp() {
        pdfNode = new FileNode();
        pdfNode.setId(100L);
        pdfNode.setTenantId(1L);
        pdfNode.setOwnerId(1L);
        pdfNode.setName("合同.pdf");
        pdfNode.setSuffix("pdf");
        pdfNode.setStatus(NodeStatus.NORMAL.getCode());
        pdfNode.setNodeType(1);
        pdfNode.setUploadStatus(UploadStatus.COMPLETED.getCode());
        pdfNode.setVersion(2);

        lenient().when(fileNodeMapper.selectById(100L)).thenReturn(pdfNode);
        lenient().when(editorProperties.getJwtSecret()).thenReturn("0123456789abcdef0123456789abcdef");
        lenient().when(editorProperties.getPublicBaseUrl()).thenReturn("http://localhost:8080");
        lenient().when(editorProperties.getUrl()).thenReturn("http://onlyoffice");
        lenient().when(jwtUtils.generateEditorToken(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn("editor-token");
    }

    @Test
    void pdfOwner_canEdit() {
        EditorConfigResponse res = service.generateConfig(100L, true, true, true, 1L, "alice", "1");

        Map<String, Object> config = res.getConfig();
        assertEquals("pdf", config.get("documentType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        assertEquals("pdf", document.get("fileType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) document.get("permissions");
        assertEquals(Boolean.TRUE, permissions.get("edit"));
        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        assertEquals("edit", editorConfig.get("mode"));
    }

    @Test
    void pdf_viewMode_isReadOnly() {
        EditorConfigResponse res = service.generateConfig(100L, false, true, true, 1L, "alice", "1");

        Map<String, Object> config = res.getConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) document.get("permissions");
        assertEquals(Boolean.FALSE, permissions.get("edit"));
        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        assertEquals("view", editorConfig.get("mode"));
    }

    @Test
    void config_includesSignedToken() {
        EditorConfigResponse res = service.generateConfig(100L, true, true, true, 1L, "alice", "1");
        assertTrue(!res.getConfig().get("token").toString().isEmpty());
    }
}
