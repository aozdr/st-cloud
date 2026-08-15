package com.stcloud.core.editor;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 在线编辑权限判定实现：
 * <ul>
 *   <li>个人文件：owner 可编辑（租户管理员直通）；非 owner 拒绝（TC-02）</li>
 *   <li>团队/分享文件：由 st-team / st-share 侧基于 upload 权限点判定后调用 config 服务</li>
 *   <li>格式：仅 docx/xlsx/pptx（TC-06；D2 决策：本迭代只接在线编辑，只读预览保持现状）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EditorPermissionServiceImpl implements EditorPermissionService {

    /**
     * OnlyOffice 支持后缀：docx/xlsx/pptx 可编辑；pdf 仅用于查看模式（前端不提供 PDF 编辑入口，
     * 后端在生成配置时对 pdf 强制 canEdit=false）
     */
    private static final Set<String> EDITABLE_SUFFIXES = Set.of("docx", "xlsx", "pptx", "pdf");

    private final FileNodeMapper fileNodeMapper;

    @Override
    public boolean isEditableSuffix(String suffix) {
        return suffix != null && EDITABLE_SUFFIXES.contains(suffix.toLowerCase());
    }

    @Override
    public void assertSupported(FileNode node) {
        if (!isEditableSuffix(node.getSuffix())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "该文件类型暂不支持在线编辑/查看（仅支持 docx/xlsx/pptx/pdf）");
        }
    }

    @Override
    public EditorAccess resolvePersonal(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅文件支持在线编辑");
        }
        if (node.getSpaceId() != null && node.getSpaceId() > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "团队文件请通过团队入口打开编辑器");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        boolean owner = node.getOwnerId() != null && node.getOwnerId().equals(userId);
        boolean tenantAdmin = UserContext.canAccessTenant();
        if (!owner && !tenantAdmin) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权编辑该文件");
        }
        assertSupported(node);
        return new EditorAccess(node, owner || tenantAdmin);
    }
}
