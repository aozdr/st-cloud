package com.stcloud.share.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.share.dto.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

public interface ShareService {

    Result<ShareVO> createShare(CreateShareRequest request);

    Result<IPage<ShareVO>> listShares(int page, int size);

    Result<Void> cancelShare(Long shareId);

    Result<Void> updateShare(Long shareId, UpdateShareRequest request);

    Result<ShareAccessVO> accessShare(ShareAccessRequest request);

    Result<String> getDownloadUrl(String shareCode, Long nodeId, String password);

    Result<List<FileNodeVO>> listShareFiles(String shareCode, Long parentId, String password);

    void streamShareFile(String shareCode, Long nodeId, String password, HttpServletResponse response);
}
