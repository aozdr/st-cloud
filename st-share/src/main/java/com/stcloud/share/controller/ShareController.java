package com.stcloud.share.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.share.dto.*;
import com.stcloud.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件分享", description = "文件链接分享、提取码访问、分享管理")
@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    // ==================== 需认证接口 ====================

    @Operation(summary = "创建分享")
    @Auditable(action = "SHARE_CREATE", targetType = "SHARE")
    @PreAuthorize("hasAuthority('share:create') or hasRole('ADMIN')")
    @PostMapping("/api/share/create")
    public Result<ShareVO> createShare(@Valid @RequestBody CreateShareRequest request) {
        return shareService.createShare(request);
    }

    @Operation(summary = "我的分享列表")
    @GetMapping("/api/share/list")
    public Result<IPage<ShareVO>> listShares(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return shareService.listShares(page, size);
    }

    @Operation(summary = "修改分享设置")
    @Auditable(action = "SHARE_UPDATE", targetType = "SHARE", targetIdParam = "shareId")
    @PutMapping("/api/share/{shareId}")
    public Result<Void> updateShare(@PathVariable Long shareId, @RequestBody UpdateShareRequest request) {
        return shareService.updateShare(shareId, request);
    }

    @Operation(summary = "取消分享")
    @Auditable(action = "SHARE_CANCEL", targetType = "SHARE", targetIdParam = "shareId")
    @PreAuthorize("hasAuthority('share:delete') or hasRole('ADMIN')")
    @DeleteMapping("/api/share/{shareId}")
    public Result<Void> cancelShare(@PathVariable Long shareId) {
        return shareService.cancelShare(shareId);
    }

    // ==================== 公开访问接口（无需认证） ====================

    @Operation(summary = "访问分享（校验提取码）")
    @Auditable(action = "SHARE_ACCESS", targetType = "SHARE")
    @PostMapping("/api/share/access/access")
    public Result<ShareAccessVO> accessShare(@Valid @RequestBody ShareAccessRequest request) {
        return shareService.accessShare(request);
    }

    @Operation(summary = "获取分享下载链接")
    @GetMapping("/api/share/access/download/{shareCode}")
    public Result<String> getDownloadUrl(
            @PathVariable String shareCode,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String password) {
        return shareService.getDownloadUrl(shareCode, nodeId, password);
    }

    @Operation(summary = "列出分享文件夹子内容")
    @GetMapping("/api/share/access/list")
    public Result<List<FileNodeVO>> listShareFiles(
            @RequestParam String shareCode,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) String password) {
        return shareService.listShareFiles(shareCode, parentId, password);
    }

    @Operation(summary = "流式预览分享文件")
    @GetMapping("/api/share/access/stream/{shareCode}")
    public void streamShareFile(
            @PathVariable String shareCode,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String password,
            HttpServletResponse response) {
        shareService.streamShareFile(shareCode, nodeId, password, response);
    }
}
