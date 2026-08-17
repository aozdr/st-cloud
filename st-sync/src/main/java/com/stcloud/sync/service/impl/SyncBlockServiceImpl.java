package com.stcloud.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import com.stcloud.sync.dto.BlockUploadRequest;
import com.stcloud.sync.dto.BlockUploadResponse;
import com.stcloud.sync.entity.FileBlock;
import com.stcloud.sync.mapper.FileBlockMapper;
import com.stcloud.sync.service.SyncBlockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 块级增量同步服务实现（迭代 5）
 * <p>
 * 大文件修改后仅上传变化块：block-check 对比块布局并初始化 multipart，block-upload 复制可复用块 + 合并 + 更新元数据。
 * 块大小 5MB（S3 multipart 最小块约束），块序号 0-based，S3 partNumber = blockIndex + 1。
 */
@Slf4j
@Service
public class SyncBlockServiceImpl implements SyncBlockService {

    private static final long BLOCK_SIZE_5MB = 5 * 1024 * 1024L;

    @Resource
    private FileBlockMapper fileBlockMapper;
    @Resource
    private FileService fileService;
    @Resource
    private StorageService storageService;
    @Resource
    private UploadStorageManager uploadStorageManager;
    @Resource
    private SyncBlockCommitManager syncBlockCommitManager;

    @Override
    // F1-2 只读方法去事务：blockCheck 仅读 DB（块布局查询），无 DB 写；
    // S3 initMultipartUpload 在事务外执行（整体无事务），S3 失败直接抛错返回（设计文档 F1-2）
    public Result<BlockCheckResponse> blockCheck(BlockCheckRequest request) {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        FileNode node = fileService.getNodeByIdAndOwner(request.getFileNodeId());
        if (node == null || !node.isFile()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "文件不存在或非文件类型");
        }
        // 校验块大小一致（5MB），避免块布局错位
        if (request.getBlockSize() == null || request.getBlockSize() != BLOCK_SIZE_5MB) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "块大小必须为5MB");
        }

        // S3 multipart 初始化移至事务外：本方法无 DB 写，不开启事务，
        // S3 网络耗时不再占用 DB 连接；S3 失败在此直接抛错返回，不留半成品状态
        String newStoragePath = tenantId + "/" + userId + "/" + request.getFileMd5()
                + "_" + System.currentTimeMillis();
        String s3UploadId = storageService.initMultipartUpload(newStoragePath);

        // 查询当前版本的块布局
        Integer currentVersion = node.getVersion() != null ? node.getVersion() : 0;
        List<FileBlock> serverBlocks = fileBlockMapper.selectList(
                new LambdaQueryWrapper<FileBlock>()
                        .eq(FileBlock::getFileNodeId, node.getId())
                        .eq(FileBlock::getVersion, currentVersion)
                        .orderByAsc(FileBlock::getBlockIndex));

        // 建索引映射：blockIndex -> blockMd5
        Map<Integer, String> serverBlockMd5Map = new HashMap<>();
        for (FileBlock fb : serverBlocks) {
            serverBlockMd5Map.put(fb.getBlockIndex(), fb.getBlockMd5());
        }

        // 逐块对比：md5 匹配 -> 可复用（服务端复制）；否则 -> 缺失（客户端上传）
        List<BlockCheckResponse.ReusableBlock> reusableBlocks = new ArrayList<>();
        List<BlockCheckResponse.PresignedBlock> missingBlocks = new ArrayList<>();
        String oldStoragePath = node.getStoragePath();

        for (BlockCheckRequest.BlockHash clientBlock : request.getBlocks()) {
            int blockIndex = clientBlock.getIndex();
            String serverMd5 = serverBlockMd5Map.get(blockIndex);
            // 可复用条件：服务端有同序号同md5的块，且旧对象存储路径存在
            if (serverMd5 != null && serverMd5.equals(clientBlock.getMd5()) && oldStoragePath != null) {
                BlockCheckResponse.ReusableBlock rb = new BlockCheckResponse.ReusableBlock();
                rb.setBlockIndex(blockIndex);
                rb.setSourceKey(oldStoragePath);
                long rangeStart = (long) blockIndex * BLOCK_SIZE_5MB;
                long rangeEnd = rangeStart + clientBlock.getSize() - 1;
                rb.setRangeStart(rangeStart);
                rb.setRangeEnd(rangeEnd);
                reusableBlocks.add(rb);
            } else {
                // 缺失块：生成预签名上传URL（partNumber = blockIndex + 1）
                String presignedUrl = storageService.presignUploadPart(
                        newStoragePath, s3UploadId, blockIndex + 1, Duration.ofHours(2));
                BlockCheckResponse.PresignedBlock pb = new BlockCheckResponse.PresignedBlock();
                pb.setBlockIndex(blockIndex);
                pb.setPresignedUrl(presignedUrl);
                missingBlocks.add(pb);
            }
        }

        BlockCheckResponse resp = new BlockCheckResponse();
        resp.setS3UploadId(s3UploadId);
        resp.setStoragePath(newStoragePath);
        resp.setReusableBlocks(reusableBlocks);
        resp.setMissingBlocks(missingBlocks);

        log.info("块级检查完成: nodeId={}, reusable={}, missing={}",
                node.getId(), reusableBlocks.size(), missingBlocks.size());
        return Result.success(resp);
    }

    @Override
    // F3（TX-04）：blockUpload 的 S3 调用全部在事务外执行，DB 写入收敛进 SyncBlockCommitManager
    // 独立事务方法。S3 复制/合并失败直接抛错返回，不开启事务、不产生半成品 DB 状态；
    // 去重命中清理在事务提交后执行（幂等，不误删被引用对象）。
    public Result<BlockUploadResponse> blockUpload(BlockUploadRequest request) {
        Long tenantId = TenantContext.getTenantId();
        FileNode node = fileService.getNodeByIdAndOwner(request.getFileNodeId());
        if (node == null || !node.isFile()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "文件不存在或非文件类型");
        }

        long oldSize = node.getFileSize() != null ? node.getFileSize() : 0L;
        Integer oldVersion = node.getVersion() != null ? node.getVersion() : 0;
        String oldStoragePath = node.getStoragePath();
        Long oldObjectId = node.getObjectId();

        // 1. 重新派生可复用块（事务外只读：仅用于计算 S3 复制范围）
        List<FileBlock> serverBlocks = fileBlockMapper.selectList(
                new LambdaQueryWrapper<FileBlock>()
                        .eq(FileBlock::getFileNodeId, node.getId())
                        .eq(FileBlock::getVersion, oldVersion)
                        .orderByAsc(FileBlock::getBlockIndex));
        Map<Integer, FileBlock> serverBlockMap = new HashMap<>();
        for (FileBlock fb : serverBlocks) {
            serverBlockMap.put(fb.getBlockIndex(), fb);
        }

        // 2. 复制可复用块到新 multipart（事务外 S3 网络调用）
        int copiedCount = 0;
        for (BlockCheckRequest.BlockHash clientBlock : request.getBlocks()) {
            int blockIndex = clientBlock.getIndex();
            FileBlock serverBlock = serverBlockMap.get(blockIndex);
            if (serverBlock != null && serverBlock.getBlockMd5().equals(clientBlock.getMd5())
                    && oldStoragePath != null) {
                long rangeStart = (long) blockIndex * BLOCK_SIZE_5MB;
                long rangeEnd = rangeStart + serverBlock.getBlockSize() - 1;
                // 块级复制：从旧版本对象按字节范围复制到新 multipart
                storageService.uploadPartCopy(oldStoragePath, rangeStart, rangeEnd,
                        request.getStoragePath(), request.getS3UploadId(), blockIndex + 1);
                copiedCount++;
            }
            // 缺失块已由客户端通过预签名URL直传S3，此处无需处理
        }

        // 3. 合并 multipart（事务外 S3 网络调用）
        storageService.completeMultipartUpload(request.getStoragePath(), request.getS3UploadId());

        // 4. 事务内统一落库：去重归属 + 节点更新 + 版本快照 + 块布局 + 差值配额 + 事件
        String mergedPath = request.getStoragePath();
        BlockUploadResponse resp = syncBlockCommitManager.commitBlockUpload(
                node, tenantId, oldSize, oldVersion, oldObjectId, request);

        // 5. 去重命中清理（事务提交后执行）：合并产物被复用对象替代且无引用，尽力删除（幂等）
        if (!mergedPath.equals(node.getStoragePath())) {
            uploadStorageManager.deleteObjectQuietly(mergedPath);
        }

        log.info("块级上传组装完成: nodeId={}, version={}, copied={}, totalBlocks={}, delta={}",
                node.getId(), node.getVersion(), copiedCount, request.getTotalBlocks(),
                request.getFileSize() - oldSize);
        return Result.success(resp);
    }
}
