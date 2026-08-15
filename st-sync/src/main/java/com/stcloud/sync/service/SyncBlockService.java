package com.stcloud.sync.service;

import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import com.stcloud.sync.dto.BlockUploadRequest;
import com.stcloud.sync.dto.BlockUploadResponse;

/**
 * 块级增量同步服务（迭代 5）
 */
public interface SyncBlockService {

    /**
     * 块级检查：对比本地块哈希与服务端当前版本块布局，初始化 multipart 并返回可复用/缺失块。
     */
    Result<BlockCheckResponse> blockCheck(BlockCheckRequest request);

    /**
     * 块级组装：复制可复用块 + 合并 multipart + 更新文件节点 + 写块布局 + 发同步事件。
     */
    Result<BlockUploadResponse> blockUpload(BlockUploadRequest request);
}
