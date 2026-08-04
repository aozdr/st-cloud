package com.stcloud.core.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.FileVersionVO;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件版本", description = "文件历史版本管理：列表、恢复")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class VersionController {

    private final VersionService versionService;
    private final FileService fileService;

    @Operation(summary = "获取文件历史版本列表")
    @GetMapping("/{nodeId}/versions")
    public Result<List<FileVersionVO>> listVersions(@PathVariable Long nodeId) {
        return Result.success(versionService.listVersions(nodeId));
    }

    @Operation(summary = "恢复到指定历史版本")
    @Auditable(action = "RESTORE_VERSION", targetType = "FILE", targetIdParam = "nodeId", detail = "恢复文件历史版本")
    @PreAuthorize("hasAuthority('file:rename') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/versions/{versionId}/restore")
    public Result<FileNodeVO> restoreVersion(@PathVariable Long nodeId, @PathVariable Long versionId) {
        return Result.success(fileService.toVO(versionService.restoreVersion(nodeId, versionId)));
    }
}