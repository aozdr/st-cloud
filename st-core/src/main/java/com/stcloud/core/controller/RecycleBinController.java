package com.stcloud.core.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.BatchIdsRequest;
import com.stcloud.core.dto.RecycleItemVO;
import com.stcloud.core.service.RecycleBinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "回收站", description = "回收站管理：列表、恢复、永久删除、清空")
@RestController
@RequestMapping("/api/recycle")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RecycleBinController {

    private final RecycleBinService recycleBinService;

    @Operation(summary = "列出回收站")
    @GetMapping("/list")
    public Result<List<RecycleItemVO>> listRecycleBin() {
        return Result.success(recycleBinService.listRecycleBin());
    }

    @Operation(summary = "恢复文件")
    @Auditable(action = "RESTORE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:delete') or hasRole('ADMIN')")
    @PostMapping("/restore")
    public Result<Void> restore(@Valid @RequestBody BatchIdsRequest request) {
        recycleBinService.restore(request.getNodeIds());
        return Result.success();
    }

    @Operation(summary = "永久删除")
    @Auditable(action = "PERMANENT_DELETE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:delete') or hasRole('ADMIN')")
    @PostMapping("/delete")
    public Result<Void> permanentDelete(@Valid @RequestBody BatchIdsRequest request) {
        recycleBinService.permanentDelete(request.getNodeIds());
        return Result.success();
    }

    @Operation(summary = "清空回收站")
    @Auditable(action = "EMPTY_RECYCLE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:delete') or hasRole('ADMIN')")
    @PostMapping("/empty")
    public Result<Void> emptyRecycleBin() {
        recycleBinService.emptyRecycleBin();
        return Result.success();
    }
}
