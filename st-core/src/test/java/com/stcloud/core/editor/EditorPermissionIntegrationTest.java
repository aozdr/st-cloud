package com.stcloud.core.editor;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在线编辑权限集成测试（H2 + 真实 Mapper）。
 * 覆盖 TC-01（owner 可编辑）、TC-02（非 owner 拒绝）、TC-06（格式不支持）、团队文件入口隔离。
 */
@Import(EditorPermissionIntegrationTest.EditorPermTestConfig.class)
class EditorPermissionIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class EditorPermTestConfig {
        @Bean
        EditorPermissionServiceImpl editorPermissionService(FileNodeMapper fileNodeMapper) {
            return new EditorPermissionServiceImpl(fileNodeMapper);
        }
    }

    @Autowired
    private EditorPermissionService editorPermissionService;

    @Test
    void owner_canEdit_docx() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        setUpUser(1L, 1L);
        EditorPermissionService.EditorAccess access = editorPermissionService.resolvePersonal(node.getId());
        assertTrue(access.isCanEdit());
    }

    @Test
    void nonOwner_forbidden() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        setUpUser(2L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> editorPermissionService.resolvePersonal(node.getId()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void unsupportedSuffix_rejected() {
        FileNode node = insertFileNode(1L, 1L, "说明.txt", 0);
        setUpUser(1L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> editorPermissionService.resolvePersonal(node.getId()));
        assertEquals(ResultCode.BUSINESS_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("暂不支持在线编辑"));
    }

    @Test
    void folder_rejected() {
        FileNode folder = insertFileNode(1L, 1L, "文件夹", 0);
        folder.setNodeType(0);
        fileNodeMapper.updateById(folder);
        setUpUser(1L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> editorPermissionService.resolvePersonal(folder.getId()));
        assertEquals(ResultCode.BUSINESS_ERROR.getCode(), ex.getCode());
    }

    @Test
    void teamFile_personalEndpointRejected() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        node.setSpaceId(9L);
        fileNodeMapper.updateById(node);
        setUpUser(1L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> editorPermissionService.resolvePersonal(node.getId()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("团队入口"));
    }

    @Test
    void recycledFile_notFound() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 1);
        setUpUser(1L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> editorPermissionService.resolvePersonal(node.getId()));
        assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
    }
}
