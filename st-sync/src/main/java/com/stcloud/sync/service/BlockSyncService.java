package com.stcloud.sync.service;

import com.stcloud.common.response.Result;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 块级增量同步服务
 */
public interface BlockSyncService {

    /**
     * 块级复用查询：对比客户端块哈希与云端版本，返回可复用/需上传块索引
     */
    Result<BlockCheckResponse> blockCheck(BlockCheckRequest request);

    /**
     * 块级上传：接收变化块 + 指定复用块，组装新文件版本
     *
     * @param fileNodeId   文件节点ID
     * @param reusableBlocks 可复用块索引（从旧版本复用）
     * @param newBlocks    新块文件数组（与 missingBlocks 对应）
     * @param blockHashes  全部块哈希列表
     * @param fileSize     文件总大小
     */
    Result<Void> blockUpload(Long fileNodeId, String reusableBlocks, MultipartFile[] newBlocks,
                             String newBlockIndexes, String blockHashes, Long fileSize);
}