package com.stcloud.core.editor;

import com.stcloud.core.entity.FileNode;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 在线编辑权限判定服务（个人 owner / 团队 upload / 分享 upload；格式支持）。
 */
public interface EditorPermissionService {

    /** 是否支持在线编辑的后缀（docx/xlsx/pptx/pdf；PDF 自 ONLYOFFICE Docs 8.1+ 起可编辑） */
    boolean isEditableSuffix(String suffix);

    /** 校验格式支持，不支持抛业务异常 */
    void assertSupported(FileNode node);

    /**
     * 个人文件访问判定：owner（或租户管理员）可编辑；非 owner 403；团队文件须走团队入口。
     */
    EditorAccess resolvePersonal(Long nodeId);

    /** 个人文件访问判定结果 */
    @Data
    @AllArgsConstructor
    class EditorAccess {
        private FileNode node;
        private boolean canEdit;
    }
}
