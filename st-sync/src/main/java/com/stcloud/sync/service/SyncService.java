package com.stcloud.sync.service;

import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.AddExclusionRequest;
import com.stcloud.sync.dto.CreateSyncRootRequest;
import com.stcloud.sync.dto.SyncDeltaResponse;
import com.stcloud.sync.dto.SyncExclusionVO;
import com.stcloud.sync.dto.SyncRootVO;
import com.stcloud.sync.dto.UpdateConflictStrategyRequest;

import java.util.List;

public interface SyncService {

    Result<SyncRootVO> createRoot(CreateSyncRootRequest request);

    Result<List<SyncRootVO>> listRoots();

    Result<Void> deleteRoot(Long rootId);

    Result<SyncRootVO> togglePause(Long rootId);

    Result<SyncDeltaResponse> delta(Long rootId, Long since, int page);

    // ==================== 选择性同步 ====================

    Result<List<SyncExclusionVO>> listExclusions(Long rootId);

    Result<SyncExclusionVO> addExclusion(Long rootId, AddExclusionRequest request);

    Result<Void> removeExclusion(Long rootId, Long exclusionId);

    // ==================== 冲突策略 ====================

    Result<SyncRootVO> updateConflictStrategy(Long rootId, UpdateConflictStrategyRequest request);
}