package com.stcloud.sync.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.AddExclusionRequest;
import com.stcloud.sync.dto.CreateSyncRootRequest;
import com.stcloud.sync.dto.SyncDeltaResponse;
import com.stcloud.sync.dto.SyncExclusionVO;
import com.stcloud.sync.dto.SyncRootVO;
import com.stcloud.sync.dto.UpdateConflictStrategyRequest;
import com.stcloud.sync.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件同步", description = "PC 客户端同步根注册、增量变更查询、选择性同步、冲突策略")
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SyncController {

    private final SyncService syncService;

    @Operation(summary = "注册同步根")
    @Auditable(action = "SYNC_ROOT_CREATE", targetType = "FILE", detail = "注册同步根")
    @PostMapping("/roots")
    public Result<SyncRootVO> createRoot(@Valid @RequestBody CreateSyncRootRequest request) {
        return syncService.createRoot(request);
    }

    @Operation(summary = "列出当前用户的同步根")
    @GetMapping("/roots")
    public Result<List<SyncRootVO>> listRoots() {
        return syncService.listRoots();
    }

    @Operation(summary = "注销同步根")
    @Auditable(action = "SYNC_ROOT_DELETE", targetType = "SYNC_ROOT", targetIdParam = "rootId", detail = "注销同步根")
    @DeleteMapping("/roots/{rootId}")
    public Result<Void> deleteRoot(@PathVariable Long rootId) {
        return syncService.deleteRoot(rootId);
    }

    @Operation(summary = "暂停/恢复同步根")
    @Auditable(action = "SYNC_ROOT_TOGGLE", targetType = "SYNC_ROOT", targetIdParam = "rootId", detail = "暂停/恢复同步")
    @PutMapping("/roots/{rootId}/pause")
    public Result<SyncRootVO> togglePause(@PathVariable Long rootId) {
        return syncService.togglePause(rootId);
    }

    @Operation(summary = "增量变更查询")
    @GetMapping("/roots/{rootId}/delta")
    public Result<SyncDeltaResponse> delta(
            @PathVariable Long rootId,
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "0") int page) {
        return syncService.delta(rootId, since, page);
    }

    // ==================== 选择性同步 ====================

    @Operation(summary = "列出同步根的排除路径")
    @GetMapping("/roots/{rootId}/exclusions")
    public Result<List<SyncExclusionVO>> listExclusions(@PathVariable Long rootId) {
        return syncService.listExclusions(rootId);
    }

    @Operation(summary = "添加排除路径")
    @Auditable(action = "SYNC_EXCLUSION_ADD", targetType = "SYNC_ROOT", targetIdParam = "rootId", detail = "添加同步排除路径")
    @PostMapping("/roots/{rootId}/exclusions")
    public Result<SyncExclusionVO> addExclusion(
            @PathVariable Long rootId,
            @Valid @RequestBody AddExclusionRequest request) {
        return syncService.addExclusion(rootId, request);
    }

    @Operation(summary = "删除排除路径")
    @Auditable(action = "SYNC_EXCLUSION_DEL", targetType = "SYNC_ROOT", targetIdParam = "rootId", detail = "删除同步排除路径")
    @DeleteMapping("/roots/{rootId}/exclusions/{exclusionId}")
    public Result<Void> removeExclusion(
            @PathVariable Long rootId,
            @PathVariable Long exclusionId) {
        return syncService.removeExclusion(rootId, exclusionId);
    }

    // ==================== 冲突策略 ====================

    @Operation(summary = "更新冲突策略")
    @Auditable(action = "SYNC_CONFLICT_STRATEGY", targetType = "SYNC_ROOT", targetIdParam = "rootId", detail = "更新冲突策略")
    @PutMapping("/roots/{rootId}/conflict-strategy")
    public Result<SyncRootVO> updateConflictStrategy(
            @PathVariable Long rootId,
            @Valid @RequestBody UpdateConflictStrategyRequest request) {
        return syncService.updateConflictStrategy(rootId, request);
    }
}