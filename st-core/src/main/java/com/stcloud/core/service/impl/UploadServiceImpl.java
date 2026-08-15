package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.*;
import com.stcloud.core.editor.EditorLockService;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.RelayBufferManager;
import com.stcloud.core.service.impl.upload.UploadChunkManager;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件上传服务编排门面（TASK-002）。
 * 职责收敛为流程编排：配额/容量检查、状态机转换与幂等守卫交给 {@link UploadManager}，
 * 分片记录交给 {@link UploadChunkManager}，S3 生命周期交给 {@link UploadStorageManager}，
 * 事件发布交给 {@link UploadEventPublisher}。本类不再直接操作分片/S3/事件细节。
 */
@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private SpeedLimitService speedLimitService;
    @Resource
    private UserTransferLimiter userTransferLimiter;
    @Resource
    private FileService fileService;
    @Resource
    private VersionService versionService;
    @Resource
    private FileObjectService fileObjectService;
    @Resource
    private UploadManager uploadManager;
    @Resource
    private UploadChunkManager chunkManager;
    @Resource
    private UploadStorageManager storageManager;
    @Resource
    private UploadEventPublisher uploadEventPublisher;
    @Resource
    private RelayBufferManager relayBufferManager;
    /** 编辑保护锁服务：生产必有；测试上下文手工装配时缺失，保护检查跳过（保持既有测试兼容） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EditorLockService editorLockService;

    private static final long SIMPLE_UPLOAD_THRESHOLD = 100 * 1024 * 1024L; // 100MB
    private static final long RELAY_CHUNK_MIN = 8192L;  // 中转小块下限 8KB
    private static final long RELAY_CHUNK_MAX = 1024 * 1024L;  // 中转小块上限 1MB
    private static final long RELAY_WINDOW_SEC = 2L;  // 中转速率窗口(秒)，relayChunkSize = rate * 窗口
    /** 引用计数：新建文件对 file_object 的初始单引用（去重对象引用 +1） */
    private static final int REF_COUNT_INITIAL = 1;

    @Override
    @Transactional
    public UploadCheckResponse checkInstantUpload(UploadCheckRequest request) {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        FileObject existingObj = fileObjectService.findByTenantAndMd5(tenantId, request.getFileMd5());
        if (existingObj != null) {
            uploadManager.checkQuotaForUpload(userId, request.getSpaceId(), request.getFileSize());
            storageManager.checkCapacity(request.getFileSize());

            String parentPath = fileService.validateAndGetParentPath(request.getParentId());
            String fileName = fileService.resolveNameConflict(request.getParentId(), request.getFileName());

            // 秒传：复用同租户同 md5 对象并 +1 引用，不重复上传物理对象
            FileObject object = fileObjectService.acquire(tenantId, request.getFileMd5(), request.getFileSize(),
                    () -> existingObj.getStoragePath());

            FileNode node = new FileNode();
            node.setParentId(request.getParentId());
            node.setNodeType(NodeType.FILE.getCode());
            node.setName(fileName);
            node.setPath(parentPath + "/" + fileName);
            node.setFileSize(request.getFileSize());
            node.setFileMd5(request.getFileMd5());
            node.setContentType(fileService.guessContentType(fileName));
            node.setSuffix(fileService.extractSuffix(fileName));
            node.setStoragePath(object.getStoragePath());
            node.setObjectId(object.getId());
            node.setStatus(NodeStatus.NORMAL.getCode());
            node.setUploadStatus(UploadStatus.COMPLETED.getCode());
            node.setOwnerId(userId);
            node.setUploaderId(userId);
            node.setSpaceId(request.getSpaceId());
            node.setRefCount(REF_COUNT_INITIAL);
            node.setVersion(0);
            fileNodeMapper.insert(node);

            uploadEventPublisher.publishCreated(node);
            uploadManager.consumeQuota(userId, node.getSpaceId(), request.getFileSize());

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

        // F6：解析有效限速（KB/s）；simpleUpload 服务端中转同样需要节流，修复小文件绕过限速
        int rateKb = SpeedLimitService.capRate(speedLimitService.resolve().getUploadSpeedLimit(), null);
        long rateBytes = (long) rateKb * 1024L;

        uploadManager.checkQuotaForUpload(userId, spaceId, fileSize);
        storageManager.checkCapacity(fileSize);

        String parentPath = fileService.validateAndGetParentPath(parentId);
        String fileName = fileService.resolveNameConflict(parentId, file.getOriginalFilename());

        String md5;
        try {
            md5 = DigestUtil.md5Hex(file.getInputStream());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }

        Long tenantId = UserContext.getTenantId();
        // 去重：同租户同 md5 已存在对象则复用（不重复上传）；否则上传规范化路径 tenantId/md5 并创建对象
        FileObject object = fileObjectService.acquire(tenantId, md5, fileSize, () -> {
            String key = tenantId + "/" + md5;
            storageManager.uploadObject(key, pacedInputStream(getInputStream(file), rateBytes, userId),
                    fileSize, file.getContentType());
            return key;
        });

        String storagePath = object.getStoragePath();

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
        node.setObjectId(object.getId());
        node.setStatus(NodeStatus.NORMAL.getCode());
        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
        node.setOwnerId(userId);
        node.setUploaderId(userId);
        node.setSpaceId(spaceId);
        node.setRefCount(REF_COUNT_INITIAL);
        node.setVersion(0);
        fileNodeMapper.insert(node);

        uploadEventPublisher.publishCreated(node);
        uploadManager.consumeQuota(userId, spaceId, fileSize);
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
            // 编辑保护提前：文件正在编辑时直接拒绝覆盖上传，避免产生 S3 分片残留（TC-19，D3 决策）
            if (existing != null && editorLockService != null) {
                editorLockService.assertNotEditing(java.util.Collections.singletonList(replaceFileId));
            }
        }
        long checkDelta = request.getFileSize() - originalSizeForCheck;
        uploadManager.checkQuotaForUpload(userId, request.getSpaceId(), checkDelta);
        storageManager.checkCapacity(checkDelta);

        String parentPath = fileService.validateAndGetParentPath(request.getParentId());
        String fileName = fileService.resolveNameConflict(request.getParentId(), request.getFileName());

        Long tenantId = UserContext.getTenantId();
        String storagePath = tenantId + "/" + userId + "/" + request.getFileMd5() + "_" + System.currentTimeMillis();

        String s3UploadId = storageManager.initMultipart(storagePath);

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
            node.setRefCount(REF_COUNT_INITIAL);
            node.setVersion(0);
            fileNodeMapper.insert(node);
        }

        String uploadId = UUID.randomUUID().toString().replace("-", "");
        chunkManager.createChunkRecords(uploadId, node.getId(), request.getTotalChunks(),
                request.getChunkSize(), originalSize);

        // 限速模式判定：有效限速 < 分片大小(5MB) 时走中转，否则直传
        int rateKb = SpeedLimitService.capRate(speedLimitService.resolve().getUploadSpeedLimit(),
                request.getClientLimit());
        long rateBytes = (long) rateKb * 1024L;
        long chunkSize = request.getChunkSize() != null ? request.getChunkSize() : 5L * 1024 * 1024;
        boolean relay = rateKb > 0 && rateBytes < chunkSize;
        String transferMode = relay ? "relay" : "direct";
        Long relayChunkSize = null;
        if (relay) {
            // relayChunkSize = clamp(rate * 窗口, 8KB, 1MB)，平衡节流精度与请求频率
            relayChunkSize = Math.max(RELAY_CHUNK_MIN, Math.min(rateBytes * RELAY_WINDOW_SEC, RELAY_CHUNK_MAX));
            // 创建中转缓冲会话，存储有效限速/小块上限/S3 上下文供 pacing 与超时 abort 使用
            relayBufferManager.createSession(uploadId, rateBytes, storagePath, s3UploadId, relayChunkSize);
        }

        return UploadInitResponse.builder()
                .uploadId(uploadId)
                .s3UploadId(s3UploadId)
                .fileId(node.getId())
                .presignedUrls(Collections.emptyList())
                .transferMode(transferMode)
                .relayChunkSize(relayChunkSize)
                .relayRateKb(relay ? (long) rateKb : null)
                .build();
    }

    @Override
    public UploadStatusResponse getUploadStatus(String uploadId, String s3UploadId) {
        List<FileChunk> allChunks = chunkManager.listChunks(uploadId);
        if (allChunks.isEmpty()) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }

        Long fileNodeId = allChunks.get(0).getFileNodeId();
        FileNode node = fileNodeMapper.selectById(fileNodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }

        // 直接查询 S3 实际已上传的分片（不依赖 DB status，DB status 在 confirm/merge 时维护）
        Set<Integer> uploadedSet = new HashSet<>(
                storageManager.listUploadedParts(node.getStoragePath(), s3UploadId));

        List<Integer> uploadedIndexes = allChunks.stream()
                .map(FileChunk::getChunkIndex)
                .filter(uploadedSet::contains)
                .sorted()
                .collect(Collectors.toList());

        // 预签名URL按分片门控签发（见 getChunkUrl），仅返回已上传分片列表
        return UploadStatusResponse.builder()
                .uploadId(uploadId)
                .uploadedChunkIndexes(uploadedIndexes)
                .presignedUrls(Collections.emptyMap())
                .build();
    }

    @Override
    public ChunkUrlResponse getChunkUrl(String uploadId, String s3UploadId, int chunkIndex, Integer clientLimit) {
        Long userId = UserContext.getUserId();
        FileChunk chunk = chunkManager.getChunk(uploadId, chunkIndex);
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
        String url = storageManager.presignPart(node.getStoragePath(), s3UploadId, chunkIndex, Duration.ofMinutes(5));
        return ChunkUrlResponse.builder().url(url).retryAfterMs(0L).build();
    }

    @Override
    public void confirmChunk(String uploadId, String s3UploadId, int chunkIndex) {
        Long userId = UserContext.getUserId();
        FileChunk chunk = chunkManager.getChunk(uploadId, chunkIndex);
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
        // 分片状态落库：0-待上传 -> 1-已上传（幂等，重复 confirm 不报错）
        chunkManager.markChunkUploaded(uploadId, chunkIndex);
        userTransferLimiter.releaseUpload(userId);
    }

    @Override
    @Transactional
    public FileNodeVO mergeChunks(UploadMergeRequest request) {
        FileChunk firstChunk = chunkManager.getFirstChunk(request.getUploadId());
        if (firstChunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }

        FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }

        // 幂等：已完成上传，直接返回已有节点（不重复合并、不产生重复节点）
        if (node.getUploadStatus() == UploadStatus.COMPLETED.getCode()) {
            return fileService.toVO(node);
        }

        // 原子认领合并：并发下仅一个请求执行 completeMultipart；未认领到则重读判断（并发幂等）
        if (!uploadManager.claimMerging(node.getId())) {
            FileNode current = fileNodeMapper.selectById(node.getId());
            if (current != null && current.getUploadStatus() == UploadStatus.COMPLETED.getCode()) {
                return fileService.toVO(current);
            }
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "上传正在合并中，请稍后查询状态");
        }

        boolean isReplaceUpload = firstChunk.getOriginalSize() != null;
        try {
            storageManager.completeMultipart(node.getStoragePath(), request.getS3UploadId());
        } catch (RuntimeException e) {
            log.error("分片合并失败: uploadId={}, error={}", request.getUploadId(), e.getMessage());
            if (isReplaceUpload) {
                // 替换上传：清理 S3 残留并恢复上一版本，既有文件保持可用
                storageManager.abortMultipart(node.getStoragePath(), request.getS3UploadId());
            }
            // 新建上传：保留 S3 分片与节点（FAILED），支持断点续传/重试
            uploadManager.handleMergeFailure(node, isReplaceUpload);
            throw e;
        }

        // 合并成功：标记分片已合并（保留记录以支撑重复 merge 幂等）
        chunkManager.markChunksMerged(request.getUploadId());

        // 去重归属对象：合并完成后按 md5 归属 file_object；同 md5 已存在则复用并清理刚合并的临时对象
        Long oldObjectId = node.getObjectId();
        Long objectTenant = node.getTenantId() != null ? node.getTenantId() : UserContext.getTenantId();
        long objectSize = node.getFileSize() == null ? 0L : node.getFileSize();
        FileObject object = fileObjectService.acquire(objectTenant, node.getFileMd5(), objectSize,
                () -> node.getStoragePath());
        if (object != null) {
            if (!object.getStoragePath().equals(node.getStoragePath())) {
                // 去重命中：合并产生的临时对象不再需要，尽力清理
                storageManager.deleteObjectQuietly(node.getStoragePath());
            }
            node.setObjectId(object.getId());
            node.setStoragePath(object.getStoragePath());
        } else {
            node.setObjectId(null);
        }
        // 替换上传：旧对象引用释放（保留物理对象，可能仍被版本历史引用）
        if (oldObjectId != null && (object == null || !oldObjectId.equals(object.getId()))) {
            fileObjectService.release(oldObjectId);
        }

        uploadManager.markCompleted(node);
        fileNodeMapper.updateById(node);
        versionService.snapshotCurrentVersion(node);

        uploadEventPublisher.publishUpdated(node);
        // 按差值计费：替换上传仅补/退新旧大小差值，新建上传 delta = 全量 fileSize
        long newSize = node.getFileSize() == null ? 0 : node.getFileSize();
        long original = firstChunk.getOriginalSize() == null ? 0 : firstChunk.getOriginalSize();
        long delta = newSize - original;
        uploadManager.consumeQuota(node.getOwnerId(), node.getSpaceId(), delta);

        return fileService.toVO(node);
    }

    @Override
    @Transactional
    public void abortUpload(String uploadId, String s3UploadId, Long fileId) {
        FileChunk firstChunk = chunkManager.getFirstChunk(uploadId);
        if (firstChunk == null) {
            return; // 幂等：无分片记录（已中止或已完成）
        }
        FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
        if (node == null) {
            chunkManager.deleteByUploadId(uploadId);
            return;
        }
        // 已完成上传不允许中止（幂等守卫，防止误删已完成文件）
        if (node.getUploadStatus() == UploadStatus.COMPLETED.getCode()) {
            return;
        }
        storageManager.abortMultipart(node.getStoragePath(), s3UploadId);
        // 有历史版本（替换上传）恢复上一版本；无历史版本（新建上传）删除 pending 节点
        uploadManager.rollbackUploadNode(node);
        chunkManager.deleteByUploadId(uploadId);
        // 中转模式：同时清理缓冲临时文件
        relayBufferManager.cleanup(uploadId);
    }

    @Override
    public RelayChunkResponse relayChunk(String uploadId, String s3UploadId, int seq,
                                         java.io.InputStream inputStream, long chunkBytes) {
        Long userId = UserContext.getUserId();
        FileChunk firstChunk = chunkManager.getFirstChunk(uploadId);
        if (firstChunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }
        FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 权限校验：仅文件 owner 或租户管理员可操作
        if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED);
        }
        // Content-Length 校验：单请求不得超过 relayChunkSize，防超大请求打满磁盘（chunked 无长度时跳过）
        long relayChunkSize = relayBufferManager.getRelayChunkSize(uploadId);
        if (relayChunkSize > 0 && chunkBytes > relayChunkSize) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "中转小块超过 relayChunkSize");
        }
        // seq 幂等：已确认过的 seq（客户端同 seq 重试）直接返回，不重复写字节
        if (!relayBufferManager.tryAcquireSeq(uploadId, seq)) {
            return RelayChunkResponse.builder()
                    .confirmed(true)
                    .partUploaded(false)
                    .partNumber(0)
                    .build();
        }
        // 从会话获取有效限速，pacing 阻塞接收（瞬时速率不超限）
        long rateBytes = relayBufferManager.getRate(uploadId);
        byte[] buffer = new byte[8192];
        int partNumber = 0;
        int n;
        try {
            while ((n = inputStream.read(buffer)) != -1) {
                userTransferLimiter.acquireUploadPace(userId, n, rateBytes);
                partNumber = relayBufferManager.appendChunk(uploadId, buffer, 0, n,
                        node.getStoragePath(), s3UploadId);
            }
        } catch (java.io.IOException e) {
            log.error("中转接收失败: uploadId={}, seq={}", uploadId, seq, e);
            relayBufferManager.cleanup(uploadId);
            storageManager.abortMultipart(node.getStoragePath(), s3UploadId);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        // 本次触发 uploadPart：同步 file_chunk 状态（0-待上传 -> 1-已上传，幂等，impact.md 遗留）
        if (partNumber > 0) {
            chunkManager.markChunkUploaded(uploadId, partNumber);
        }
        return RelayChunkResponse.builder()
                .confirmed(true)
                .partUploaded(partNumber > 0)
                .partNumber(partNumber)
                .build();
    }

    @Override
    public FileNodeVO relayFinalize(String uploadId, String s3UploadId) {
        Long userId = UserContext.getUserId();
        FileChunk firstChunk = chunkManager.getFirstChunk(uploadId);
        if (firstChunk == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }
        FileNode node = fileNodeMapper.selectById(firstChunk.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 权限校验：与 relayChunk 一致，仅文件 owner 或租户管理员可操作
        if (!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED);
        }
        try {
            // 上传末片（余量 < 5MB，无下限），然后复用 merge 逻辑完成合并
            int lastPart = relayBufferManager.finalize(uploadId, node.getStoragePath(), s3UploadId);
            if (lastPart > 0) {
                chunkManager.markChunkUploaded(uploadId, lastPart);
            }
            // 复用现有 merge 流程：claimMerging + completeMultipartUpload + 去重 + 配额 + 事件
            UploadMergeRequest mergeRequest = new UploadMergeRequest();
            mergeRequest.setUploadId(uploadId);
            mergeRequest.setS3UploadId(s3UploadId);
            mergeRequest.setFileId(node.getId());
            return mergeChunks(mergeRequest);
        } catch (RuntimeException e) {
            // 失败即清理：删临时文件 + abort S3（MVP 不支持中转断点续传，失败重来；替换上传由 mergeChunks 已恢复旧版本）
            log.error("中转 finalize 失败: uploadId={}, error={}", uploadId, e.getMessage());
            relayBufferManager.cleanup(uploadId);
            try {
                storageManager.abortMultipart(node.getStoragePath(), s3UploadId);
            } catch (Exception abortEx) {
                log.warn("中转失败后 abort S3 失败(可能已中止): uploadId={}, error={}", uploadId, abortEx.getMessage());
            }
            throw e;
        }
    }

    /** 限速 InputStream（F6）：按 8KB 步进调用 acquireUploadPace 阻塞 pacing，使 simpleUpload 服务端接收不绕过限速 */
    private java.io.InputStream pacedInputStream(java.io.InputStream delegate, long rateBytes, Long userId) {
        if (rateBytes <= 0) {
            return delegate;
        }
        return new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = delegate.read(one);
                if (n > 0) {
                    userTransferLimiter.acquireUploadPace(userId, n, rateBytes);
                }
                return n;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = delegate.read(b, off, Math.min(len, 8192));
                if (n > 0) {
                    userTransferLimiter.acquireUploadPace(userId, n, rateBytes);
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };
    }

    private java.io.InputStream getInputStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }
}
