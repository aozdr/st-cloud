package com.stcloud.share.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.core.editor.dto.EditorConfigResponse;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.share.dto.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

public interface ShareService {

    Result<ShareVO> createShare(CreateShareRequest request);

    Result<IPage<ShareVO>> listShares(int page, int size);

    Result<Void> cancelShare(Long shareId);

    Result<Void> updateShare(Long shareId, UpdateShareRequest request);

    Result<ShareAccessVO> accessShare(ShareAccessRequest request);

    Result<String> getDownloadUrl(String shareCode, Long nodeId, String password);

    Result<List<FileNodeVO>> listShareFiles(String shareCode, Long parentId, String password);

    void streamShareFile(String shareCode, Long nodeId, String password, HttpServletResponse response);

    /**
     * 当前用户对文件的有效权限集（个人/团队分支，未授权返回空集），供分享权限点选择/禁用。
     */
    Result<Map<String, Boolean>> effectivePermissions(Long fileNodeId);

    /**
     * 分享文件在线编辑配置（分享权限集含 upload 可编辑；nodeId 为分享文件夹内的子文件时校验归属）。
     */
    Result<EditorConfigResponse> editorConfig(String shareCode, Long nodeId, String password);
}
