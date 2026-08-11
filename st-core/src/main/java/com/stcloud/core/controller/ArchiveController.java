package com.stcloud.core.controller;

import com.stcloud.common.response.Result;
import com.stcloud.core.service.ArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 在线解压：支持 ZIP 格式压缩包的在线浏览与解压
 */
@Tag(name = "在线解压", description = "压缩包在线浏览与解压")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ArchiveController {

    @Resource
    private ArchiveService archiveService;

    @Operation(summary = "浏览压缩包内容列表")
    @GetMapping("/{nodeId}/archive/contents")
    public Result<List<Map<String, Object>>> listArchiveContents(@PathVariable Long nodeId) {
        return Result.success(archiveService.listArchiveContents(nodeId));
    }

    @Operation(summary = "解压文件到指定目录")
    @PostMapping("/{nodeId}/archive/extract")
    public Result<Integer> extractArchive(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "0") Long targetFolderId) {
        return Result.success(archiveService.extractArchive(nodeId, targetFolderId));
    }
}
