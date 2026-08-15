package com.stcloud.sync.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import com.stcloud.sync.dto.BlockUploadRequest;
import com.stcloud.sync.dto.BlockUploadResponse;
import com.stcloud.sync.service.SyncBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 块级增量同步接口（迭代 5）
 * <p>
 * 大文件修改后仅上传变化块：block-check 对比块布局，block-upload 复制可复用块 + 合并新版本。
 */
@Tag(name = "文件同步-块级增量", description = "大文件块级增量同步")
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SyncBlockController {

    private final SyncBlockService syncBlockService;

    @Operation(summary = "块级检查：对比块布局并初始化上传")
    @Auditable(action = "SYNC_BLOCK_CHECK", targetType = "FILE", detail = "块级同步检查")
    @PostMapping("/block-check")
    public Result<BlockCheckResponse> blockCheck(@Valid @RequestBody BlockCheckRequest request) {
        return syncBlockService.blockCheck(request);
    }

    @Operation(summary = "块级组装：复制可复用块 + 合并新版本")
    @Auditable(action = "SYNC_BLOCK_UPLOAD", targetType = "FILE", detail = "块级同步上传")
    @PostMapping("/block-upload")
    public Result<BlockUploadResponse> blockUpload(@Valid @RequestBody BlockUploadRequest request) {
        return syncBlockService.blockUpload(request);
    }
}
