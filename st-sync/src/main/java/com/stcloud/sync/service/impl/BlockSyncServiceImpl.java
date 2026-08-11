package com.stcloud.sync.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileChunkMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockCheckResponse;
import com.stcloud.sync.service.BlockSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 块级增量同步服务实现
 * <p>
 * 复用现有 file_chunk + file_version + S3 存储基础设施。
 * 块大小与桌面端一致（5MB），文件 > 8MB 走块级，否则走全量上传。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockSyncServiceImpl implements BlockSyncService {

    private final FileNodeMapper fileNodeMapper;
    private final FileVersionMapper fileVersionMapper;
    private final FileChunkMapper fileChunkMapper;
    private final FileService fileService;
    private final StorageService storageService;

    /** 块大小：5MB（与桌面端 CHUNK_SIZE 一致） */
    private static final long BLOCK_SIZE = 5 * 1024 * 1024L;
    /** 块级同步阈值：文件 > 8MB 走块级 */
    private static final long BLOCK_SYNC_THRESHOLD = 8 * 1024 * 1024L;

    /**
     * 块级复用查询
     * <p>
     * 1. 查询云端文件节点的最新版本
     * 2. 若全文件 MD5 一致，返回 cloudExists=true + cloudMd5（客户端跳过上传）
     * 3. 否则从 S3 下载云端版本，按块计算哈希，对比客户端传入的块哈希
     * 4. 返回可复用块索引和需上传块索引
     */
    @Override
    public Result<BlockCheckResponse> blockCheck(BlockCheckRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        FileNode node = fileNodeMapper.selectById(request.getFileNodeId());
        if (node == null || !userId.equals(node.getOwnerId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "文件不存在或无权限");
        }

        BlockCheckResponse response = new BlockCheckResponse();
        response.setBlockSize(BLOCK_SIZE);

        // 查询最新版本
        FileVersion latestVersion = fileVersionMapper.selectOne(
                new LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileNodeId, request.getFileNodeId())
                        .orderByDesc(FileVersion::getVersionNum)
                        .last("LIMIT 1"));

        if (latestVersion == null) {
            // 云端无历史版本，需全量上传
            response.setCloudExists(false);
            response.setReusableBlocks(List.of());
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < request.getBlockHashes().size(); i++) {
                missing.add(i);
            }
            response.setMissingBlocks(missing);
            return Result.success(response);
        }

        response.setCloudExists(true);
        response.setCloudMd5(latestVersion.getFileMd5());

        // 全文件 MD5 一致，无需块级同步
        if (latestVersion.getFileMd5() != null && !latestVersion.getFileMd5().isEmpty()) {
            // 客户端会对比 cloudMd5 与本地 MD5，一致则跳过
        }

        // 从 S3 下载云端版本，按块计算哈希对比
        List<Integer> reusable = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        List<String> clientHashes = request.getBlockHashes();

        try (InputStream cloudStream = storageService.downloadObject(latestVersion.getStoragePath())) {
            byte[] buffer = new byte[(int) BLOCK_SIZE];
            int blockIndex = 0;
            int bytesRead;

            while ((bytesRead = readBlock(cloudStream, buffer)) > 0) {
                String cloudBlockHash = DigestUtil.md5Hex(new ByteArrayInputStream(buffer, 0, bytesRead));

                if (blockIndex < clientHashes.size() && clientHashes.get(blockIndex).equals(cloudBlockHash)) {
                    reusable.add(blockIndex);
                } else {
                    missing.add(blockIndex);
                }
                blockIndex++;
            }

            // 客户端块数多于云端，多出的块都需上传
            for (int i = blockIndex; i < clientHashes.size(); i++) {
                missing.add(i);
            }
        } catch (Exception e) {
            log.error("块级复用查询失败: fileNodeId={}, error={}", request.getFileNodeId(), e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "块级查询失败: " + e.getMessage());
        }

        response.setReusableBlocks(reusable);
        response.setMissingBlocks(missing);
        return Result.success(response);
    }

    /**
     * 块级上传：接收新块 + 从旧版本复用块，组装新版本
     */
    @Override
    @Transactional
    public Result<Void> blockUpload(Long fileNodeId, String reusableBlocks, MultipartFile[] newBlocks,
                                     String newBlockIndexes, String blockHashes, Long fileSize) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        FileNode node = fileNodeMapper.selectById(fileNodeId);
        if (node == null || !userId.equals(node.getOwnerId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "文件不存在或无权限");
        }

        // 解析参数
        String[] reusableArr = reusableBlocks.split(",");
        String[] newIndexArr = newBlockIndexes.split(",");
        String[] hashArr = blockHashes.split(",");

        int totalBlocks = hashArr.length;

        // 查询旧版本用于复用块
        FileVersion oldVersion = fileVersionMapper.selectOne(
                new LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileNodeId, fileNodeId)
                        .orderByDesc(FileVersion::getVersionNum)
                        .last("LIMIT 1"));

        // 计算新文件 MD5（拼接所有块哈希后取 MD5 作为文件标识）
        String fileMd5 = DigestUtil.md5Hex(blockHashes);

        // 组装新版本存储路径
        String storagePath = node.getTenantId() + "/" + userId + "/" + fileMd5 + "_" + System.currentTimeMillis();

        // 将新块上传到 S3 临时位置，然后从旧版本复用块拼接组装完整文件
        // 策略：逐块从旧版本下载（复用块）或从上传的新块读取，拼接后上传到新 storagePath
        try (java.io.ByteArrayOutputStream assembly = new java.io.ByteArrayOutputStream()) {
            // 构建块映射：index -> 数据来源
            java.util.Map<Integer, byte[]> newBlockData = new java.util.HashMap<>();
            for (int i = 0; i < newBlocks.length && i < newIndexArr.length; i++) {
                int idx = Integer.parseInt(newIndexArr[i].trim());
                newBlockData.put(idx, newBlocks[i].getBytes());
            }

            // 逐块拼接
            for (int i = 0; i < totalBlocks; i++) {
                if (newBlockData.containsKey(i)) {
                    // 新块
                    assembly.write(newBlockData.get(i));
                } else if (oldVersion != null && java.util.Arrays.asList(reusableArr).contains(String.valueOf(i))) {
                    // 复用块：从旧版本 S3 对象按范围下载
                    long start = (long) i * BLOCK_SIZE;
                    long end = Math.min(start + BLOCK_SIZE - 1, (oldVersion.getFileSize() != null ? oldVersion.getFileSize() : fileSize) - 1);
                    try (InputStream rangeStream = storageService.downloadObjectRange(oldVersion.getStoragePath(), start, end)) {
                        byte[] buf = rangeStream.readAllBytes();
                        assembly.write(buf);
                    }
                }
            }

            // 上传组装后的完整文件到 S3
            byte[] fileBytes = assembly.toByteArray();
            storageService.uploadObject(storagePath, new ByteArrayInputStream(fileBytes), fileBytes.length, node.getContentType());

            // 创建新版本记录
            FileVersion newVersion = new FileVersion();
            newVersion.setFileNodeId(fileNodeId);
            newVersion.setVersionNum((oldVersion != null ? oldVersion.getVersionNum() : 0) + 1);
            newVersion.setFileSize(fileSize);
            newVersion.setFileMd5(fileMd5);
            newVersion.setStoragePath(storagePath);
            newVersion.setModifierId(userId);
            fileVersionMapper.insert(newVersion);

            // 更新文件节点指向新版本
            node.setFileMd5(fileMd5);
            node.setFileSize(fileSize);
            node.setStoragePath(storagePath);
            fileNodeMapper.updateById(node);

        } catch (Exception e) {
            log.error("块级上传失败: fileNodeId={}, error={}", fileNodeId, e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "块级上传失败: " + e.getMessage());
        }

        return Result.success();
    }

    /** 读取一个块的数据，返回实际读取字节数 */
    private int readBlock(InputStream stream, byte[] buffer) throws java.io.IOException {
        int totalRead = 0;
        int offset = 0;
        int remaining = buffer.length;
        while (remaining > 0) {
            int read = stream.read(buffer, offset, remaining);
            if (read == -1) break;
            offset += read;
            totalRead += read;
            remaining -= read;
        }
        return totalRead;
    }
}