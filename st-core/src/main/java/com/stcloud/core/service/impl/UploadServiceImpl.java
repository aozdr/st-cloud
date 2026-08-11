package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.*;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileChunkMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FileChunkMapper fileChunkMapper;
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper;
    @Resource
    private StorageService storageService;
    @Resource
    private SpeedLimitService speedLimitService;
    @Resource
    private UserTransferLimiter userTransferLimiter;
    @Resource
    private FileService fileService;
    @Resource
    private VersionService versionService;
    @Resource
    private ApplicationEventPublisher eventPublisher;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;

    private static final long SIMPLE_UPLOAD_THRESHOLD = 100 * 1024 * 1024L; // 100MB

    @Override
    @Transactional
    public UploadCheckResponse checkInstantUpload(UploadCheckRequest request) {
        Long userId = UserContext.getUserId();

        FileNode existing = fileNodeMapper.selectByMd5(request.getFileMd5());
        if (existing != null) {
            if (request.getSpaceId() != null && request.getSpaceId() > 0) {
                checkTeamQuota(request.getSpaceId(), request.getFileSize());
            } else {
                checkQuota(userId, request.getFileSize());
            }
            cloudStorageService.checkCapacity(request.getFileSize());

            String parentPath = fileService.validateAndGetParentPath(request.getParentId());
            String fileName = fileService.resolveNameConflict(request.getParentId(), request.getFileName());

            FileNode node = new FileNode();
            node.setParentId(request.getParentId());
            node.setNodeType(NodeType.FILE.getCode());
            node.setName(fileName);
            node.setPath(parentPath + "/" + fileName);
            node.setFileSize(request.getFileSize());
            node.setFileMd5(request.getFileMd5());
            node.setContentType(fileService.guessContentType(fileName));
            node.setSuffix(fileService.extractSuffix(fileName));
            node.setStoragePath(existing.getStoragePath());
            node.setStatus(NodeStatus.NORMAL.getCode());
            node.setUploadStatus(UploadStatus.COMPLETED.getCode());
            node.setOwnerId(userId);
            node.setUploaderId(userId);
            node.setSpaceId(request.getSpaceId());
            node.setRefCount(0);
            node.setVersion(0);
            fileNodeMapper.insert(node);

            eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX));
            eventPublisher.publishEvent(new SyncChangeEvent(this, node, SyncChangeEvent.ChangeType.CREATE));
            fileService.incrementRefCount(request.getFileMd5());
            if (node.getSpaceId() != null && node.getSpaceId() > 0) {
                teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), request.getFileSize());
            } else {
                userQuotaMapper.updateStorageUsed(userId, request.getFileSize());
            }

            return UploadCheckResponse.builder().instant(true).fileId(node.getId()).build();
        }
        return UploadCheckResponse.builder().instant(false).build();
    }

    @Override
    @Transactional
    public FileNodeVO simpleUpload(Long parentId, MultipartFile file, Long spaceId) {
        Long userId = UserContext.getUserId();
        long fileSize = file.getSize();

        if (fileSize > SIMPLE_UPLOAD_THRESHOLD) {
            throw new BusinessException(ResultCode.FILE_TOO_LARGE.getCode(),
                    "简单上传限制5MB以内，请使用分片上传");
        }

        if (spaceId != null && spaceId > 0) {
            checkTeamQuota(spaceId, fileSize);
        } else {
            checkQuota(userId, fileSize);
        }
        cloudStorageService.checkCapacity(fileSize);

        String parentPath = fileService.validateAndGetParentPath(parentId);
        String fileName = fileService.resolveNameConflict(parentId, file.getOriginalFilename());

        String md5;
        try {
            md5 = DigestUtil.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }

        Long tenantId = UserContext.getTenantId();
        String storagePath = tenantId + "/" + userId + "/" + md5 + "_" + System.currentTimeMillis();

        try {
            storageService.uploadObject(storagePath, file.getInputStream(), fileSize, file.getContentType());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }

        FileNode node = new FileNode();
        node.setParentId(parentId);
        node.setNodeType(NodeType.FILE.getCode());
        node.setName(fileName);
        node.setPath(parentPath + "/" + fileName);
        node.setFileSize(fileSize);
        node.setFileMd5(md5);
        node.setContentType(file.getContentType());
        node.setSuffix(fileService.extractSuffix(fileName));
        node.setStoragePath(storagePath);
        node.setStatus(NodeStatus.NORMAL.getCode());
        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
        node.setOwnerId(userId);
        node.setUploaderId(userId);
        node.setSpaceId(spaceId);
        node.setRefCount(1);
        node.setVersion(0);
        fileNodeMapper.insert(node);

        eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX));
        eventPublisher.publishEvent(new SyncChangeEvent(this, node, SyncChangeEvent.ChangeType.CREATE));
        if (spaceId != null && spaceId > 0) {
            teamStorageMapper.updateTeamStorageUsed(spaceId, fileSize);
        } else {
            userQuotaMapper.updateStorageUsed(userId, fileSize);
        }
        return fileService.toVO(node);
    }

    @Override
    @Transactional
    public UploadInitResponse initChunkedUpload(UploadInitRequest request) {
        Long userId = UserContext.getUserId();
        Long replaceFileId = request.getReplaceFileId();
        // 替换上传按增量校验：增量 = 新大小 - 原大小；新建上传增量 = 全量
        long originalSizeForCheck = 0;
        if (replaceFileId != null && replaceFileId > 0) {
            FileNode existing = fileNodeMapper.selectById(replaceFileId);
            if (existing != null && existing.getFileSize() != null) {
                originalSizeForCheck = existing.getFileSize();
            }
        }
        long checkDelta = request.getFileSize() - originalSizeForCheck;
        if (request.getSpaceId() != null && request.getSpaceId() > 0) {
            checkTeamQuota(request.getSpaceId(), checkDelta);
        } else {
            checkQuota(userId, checkDelta);
        }
        cloudStorageService.checkCapacity(checkDelta);

        String parentPath = fileService.validateAndGetParentPath(request.getParentId());
        String fileName = fileService.resolveNameConflict(request.getParentId(), request.getFileName());

        Long tenantId = UserContext.getTenantId();
        String storagePath = tenantId + "/" + userId + "/" + request.getFileMd5() + "_" + System.currentTimeMillis();

        String s3UploadId = storageService.initMultipartUpload(storagePath);

        FileNode node;
        Long originalSize = null;
        if (replaceFileId != null && replaceFileId > 0) {
            node = fileNodeMapper.selectById(replaceFileId);
            if (node == null) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
            if (!node.isFile()) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅文件支持替换上传");
            }
            if (!node.isNormal()) {
                throw new BusinessException(ResultCode.FILE_IN_RECYCLE);
            }
            if (versionService.getLatestVersion(node.getId()) == null) {
                versionService.snapshotCurrentVersion(node);
            }
            originalSize = node.getFileSize();
            node.setStoragePath(storagePath);
            node.setFileMd5(request.getFileMd5());
            node.setFileSize(request.getFileSize());
            node.setContentType(fileService.guessContentType(fileName));
            node.setSuffix(fileService.extractSuffix(fileName));
            node.setUploadStatus(UploadStatus.UPLOADING.getCode());
            fileNodeMapper.updateById(node);
        } else {
            node = new FileNode();
            node.setParentId(request.getParentId());
            node.setNodeType(NodeType.FILE.getCode());
            node.setName(fileName);
            node.setPath(parentPath + "/" + fileName);
            node.setFileSize(request.getFileSize());
            node.setFileMd5(request.getFileMd5());
            node.setContentType(fileService.guessContentType(fileName));
            node.setSuffix(fileService.extractSuffix(fileName));
            node.setStoragePath(storagePath);
            node.setStatus(NodeStatus.NORMAL.getCode());
            node.setUploadStatus(UploadStatus.UPLOADING.getCode());
            node.setOwnerId(userId);
            node.setUploaderId(userId);
            node.setSpaceId(request.getSpaceId());
            node.setRefCount(1);
            node.setVersion(0);
            fileNodeMapper.insert(node);
        }

        String uploadId = UUID.randomUUID().toString().replace("-", "");
        for (int i = 1; i <= request.getTotalChunks(); i++) {
            FileChunk chunk = new FileChunk();
            chunk.setUploadId(uploadId);
            chunk.setFileNodeId(node.getId());
            chunk.setChunkIndex(i);
            chunk.setChunkSize(request.getChunkSize());
            chunk.setOriginalSize(originalSize);
            chunk.setStatus(0);
            fileChunkMapper.insert(chunk);
        }

        // 预签名URL改为按分片门控签发（见 getChunkUrl），不再一次性返回
        return UploadInitResponse.builder()
                .uploadId(uploadId)
                .s3UploadId(s3UploadId)
                .fileId(node.getId())
                .presignedUrls(Collections.emptyList())
                .build();
    }

    @Override
    public UploadStatusResponse getUploadStatus(String uploadId, String s3UploadId) {
        LambdaQueryWrapper<FileChunk> chunkWrapper = new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .orderByAsc(FileChunk::getChunkIndex);
        List<FileChunk> allChunks = fileChunkMapper.selectList(chunkWrapper);

        if (allChunks.isEmpty()) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }

        Long fileNodeId = allChunks.get(0).getFileNodeId();
        FileNode node = fileNodeMapper.selectById(fileNodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }

        // 直接查询 S3 实际已上传的分片（不依赖 DB status，DB status 仅在 merge 时统一更新）
        Set<Integer> uploadedSet = new HashSet<>(
                storageService.listUploadedParts(node.getStoragePath(), s3UploadId));

        List<Integer> uploadedIndexes = allChunks.stream()
                .map(FileChunk::getChunkIndex)
                .filter(uploadedSet::contains)
                .sorted()
                .collect(Collectors.toList());

        // 预签名URL改为按分片门控签发（见 getChunkUrl），仅返回已上传分片列表
        return UploadStatusResponse.builder()
                .uploadId(uploadId)
                .uploadedChunkIndexes(uploadedIndexes)
                .presignedUrls(Collections.emptyMap())
                .build();
    }

    @Override
    public ChunkUrlResponse getChunkUrl(String uploadId, String s3UploadId, int chunkIndex, Integer clientLimit) {
        Long userId = UserContext.getUserId();
        FileChunk chunk = fileChunkMapper.selectOne(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .eq(FileChunk::getChunkIndex, chunkIndex));
        if (chunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }
        FileNode node = fileNodeMapper.selectById(chunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED);
        }
        int rateKb = SpeedLimitService.capRate(speedLimitService.resolve().getUploadSpeedLimit(), clientLimit);
        long rateBytes = rateKb * 1024L;
        long chunkBytes = chunk.getChunkSize() != null ? chunk.getChunkSize() : 5L * 1024 * 1024;
        UserTransferLimiter.AcquireResult result = userTransferLimiter.tryAcquireUpload(userId, chunkBytes, rateBytes);
        if (!result.isAllowed()) {
            return ChunkUrlResponse.builder().url(null).retryAfterMs(result.getRetryAfterMs()).build();
        }
        String url = storageService.presignUploadPart(node.getStoragePath(), s3UploadId, chunkIndex, Duration.ofMinutes(5));
        return ChunkUrlResponse.builder().url(url).retryAfterMs(0L).build();
    }

    @Override
    public void confirmChunk(String uploadId, String s3UploadId, int chunkIndex) {
        Long userId = UserContext.getUserId();
        FileChunk chunk = fileChunkMapper.selectOne(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .eq(FileChunk::getChunkIndex, chunkIndex));
        if (chunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }
        FileNode node = fileNodeMapper.selectById(chunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED);
        }
        userTransferLimiter.releaseUpload(userId);
    }

    @Override
    @Transactional
    public FileNodeVO mergeChunks(UploadMergeRequest request) {
        LambdaQueryWrapper<FileChunk> chunkWrapper = new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, request.getUploadId())
                .last("LIMIT 1");
        FileChunk firstChunk = fileChunkMapper.selectOne(chunkWrapper);
        if (firstChunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }

        FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }

        try {
            storageService.completeMultipartUpload(node.getStoragePath(), request.getS3UploadId());
        } catch (RuntimeException e) {
            log.error("分片合并失败，清理残留上传: uploadId={}, error={}", request.getUploadId(), e.getMessage());
            cleanupFailedUpload(node, request.getS3UploadId(), request.getUploadId());
            throw e;
        }

        LambdaUpdateWrapper<FileChunk> updateWrapper = new LambdaUpdateWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, request.getUploadId())
                .set(FileChunk::getStatus, 1);
        fileChunkMapper.update(null, updateWrapper);

        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
        fileNodeMapper.updateById(node);
        versionService.snapshotCurrentVersion(node);

        eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX));
        eventPublisher.publishEvent(new SyncChangeEvent(this, node, SyncChangeEvent.ChangeType.UPDATE));
        // 按差值计费：替换上传仅补/退新旧大小差值，新建上传 delta = 全量 fileSize
        long newSize = node.getFileSize() == null ? 0 : node.getFileSize();
        long original = firstChunk.getOriginalSize() == null ? 0 : firstChunk.getOriginalSize();
        long delta = newSize - original;
        if (delta != 0) {
            if (node.getSpaceId() != null && node.getSpaceId() > 0) {
                teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta);
            } else {
                userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            }
        }
        fileChunkMapper.deleteByUploadId(request.getUploadId());

        return fileService.toVO(node);
    }

    @Override
    @Transactional
    public void abortUpload(String uploadId, String s3UploadId, Long fileId) {
        LambdaQueryWrapper<FileChunk> chunkWrapper = new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .last("LIMIT 1");
        FileChunk firstChunk = fileChunkMapper.selectOne(chunkWrapper);
        if (firstChunk != null) {
            FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
            if (node != null) {
                storageService.abortMultipartUpload(node.getStoragePath(), s3UploadId);
                FileVersion latest = versionService.getLatestVersion(node.getId());
                if (latest != null) {
                    node.setStoragePath(latest.getStoragePath());
                    node.setFileMd5(latest.getFileMd5());
                    node.setFileSize(latest.getFileSize());
                    node.setUploadStatus(UploadStatus.COMPLETED.getCode());
                    fileNodeMapper.updateById(node);
                } else {
                    fileNodeMapper.deleteById(node.getId());
                }
            }
        }
        fileChunkMapper.deleteByUploadId(uploadId);
    }

    private void cleanupFailedUpload(FileNode node, String s3UploadId, String uploadId) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> {
            try {
                storageService.abortMultipartUpload(node.getStoragePath(), s3UploadId);
                FileVersion latest = versionService.getLatestVersion(node.getId());
                if (latest != null) {
                    node.setStoragePath(latest.getStoragePath());
                    node.setFileMd5(latest.getFileMd5());
                    node.setFileSize(latest.getFileSize());
                    node.setUploadStatus(UploadStatus.COMPLETED.getCode());
                    fileNodeMapper.updateById(node);
                } else {
                    fileNodeMapper.deleteById(node.getId());
                }
                fileChunkMapper.deleteByUploadId(uploadId);
            } catch (Exception ex) {
                log.warn("清理残留上传失败: uploadId={}, error={}", uploadId, ex.getMessage());
            }
        });
    }

    private void checkQuota(Long userId, long fileSize) {
        if (fileSize <= 0) {
            return;
        }
        StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
    }

    /**
     * 团队空间配额检查
     */
    private void checkTeamQuota(Long spaceId, long fileSize) {
        if (fileSize <= 0 || spaceId == null || spaceId <= 0) {
            return;
        }
        StorageInfoVO quota = teamStorageMapper.getTeamSpaceQuota(spaceId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
            }
        }
    }
}
