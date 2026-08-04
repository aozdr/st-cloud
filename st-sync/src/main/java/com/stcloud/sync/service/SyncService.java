package com.stcloud.sync.service;

import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.CreateSyncRootRequest;
import com.stcloud.sync.dto.SyncDeltaResponse;
import com.stcloud.sync.dto.SyncRootVO;

import java.util.List;

public interface SyncService {

    Result<SyncRootVO> createRoot(CreateSyncRootRequest request);

    Result<List<SyncRootVO>> listRoots();

    Result<Void> deleteRoot(Long rootId);

    Result<SyncRootVO> togglePause(Long rootId);

    Result<SyncDeltaResponse> delta(Long rootId, Long since, int page);
}