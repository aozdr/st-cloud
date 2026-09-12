package com.stcloud.core.service.impl.upload;

import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.UploadSession;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UploadSessionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 上传初始化的唯一数据库提交边界；调用方在事务外完成 S3 初始化和失败补偿。 */
@Component
public class UploadInitCommitManager {

    @Resource private FileNodeMapper fileNodeMapper;
    @Resource private UploadSessionMapper uploadSessionMapper;
    @Resource private VersionService versionService;
    @Resource private FileService fileService;
    @Resource private UploadChunkManager chunkManager;

    public record InitCommand(UploadInitRequest request, Long userId, Long tenantId,
                              String parentPath, String fileName, String storagePath,
                              String s3UploadId, String uploadId) { }

    public record InitResult(Long fileNodeId) { }

    @Transactional
    public InitResult commitInit(InitCommand command) {
        UploadInitRequest request = command.request();
        FileNode node;
        Long originalSize = null;
        Long replaceFileId = request.getReplaceFileId();
        if (replaceFileId != null && replaceFileId > 0) {
            // 锁、授权和版本快照必须处于同一事务；异常时旧节点与版本一起回滚。
            node = fileNodeMapper.selectByIdForUpdate(replaceFileId);
            if (node == null) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            validateReplacementScope(command, node);
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
            node.setStoragePath(command.storagePath());
            node.setFileMd5(request.getFileMd5());
            node.setFileSize(request.getFileSize());
            node.setContentType(fileService.guessContentType(command.fileName()));
            node.setSuffix(fileService.extractSuffix(command.fileName()));
            node.setUploadStatus(UploadStatus.UPLOADING.getCode());
            if (fileNodeMapper.updateById(node) != 1) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件已被其他请求修改，请刷新后重试");
            }
        } else {
            node = new FileNode();
            node.setParentId(request.getParentId());
            node.setNodeType(NodeType.FILE.getCode());
            node.setName(command.fileName());
            node.setPath(command.parentPath() + "/" + command.fileName());
            node.setFileSize(request.getFileSize());
            node.setFileMd5(request.getFileMd5());
            node.setContentType(fileService.guessContentType(command.fileName()));
            node.setSuffix(fileService.extractSuffix(command.fileName()));
            node.setStoragePath(command.storagePath());
            node.setStatus(NodeStatus.NORMAL.getCode());
            node.setUploadStatus(UploadStatus.UPLOADING.getCode());
            node.setOwnerId(command.userId());
            node.setUploaderId(command.userId());
            node.setSpaceId(request.getSpaceId());
            node.setRefCount(1);
            node.setVersion(0);
            if (fileNodeMapper.insert(node) != 1) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "上传节点创建失败");
            }
        }

        UploadSession session = new UploadSession();
        session.setTenantId(command.tenantId());
        session.setUploadId(command.uploadId());
        session.setUserId(command.userId());
        session.setFileNodeId(node.getId());
        session.setSpaceId(request.getSpaceId());
        session.setStoragePath(command.storagePath());
        session.setS3UploadId(command.s3UploadId());
        session.setFileSize(request.getFileSize());
        session.setFileMd5(request.getFileMd5());
        session.setTotalChunks(request.getTotalChunks());
        session.setChunkSize(request.getChunkSize());
        session.setClientLimit(request.getClientLimit());
        session.setStatus(0);
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        if (uploadSessionMapper.insert(session) != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "上传会话创建失败");
        }
        chunkManager.createChunkRecords(command.uploadId(), node.getId(), request.getTotalChunks(),
                request.getChunkSize(), originalSize);
        return new InitResult(node.getId());
    }

    private void validateReplacementScope(InitCommand command, FileNode node) {
        if (command.tenantId() == null || !command.tenantId().equals(node.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        Long spaceId = command.request().getSpaceId();
        if (spaceId != null && spaceId > 0) {
            if (!spaceId.equals(node.getSpaceId())) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
        } else if (!command.userId().equals(node.getOwnerId())
                || (node.getSpaceId() != null && node.getSpaceId() > 0)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
