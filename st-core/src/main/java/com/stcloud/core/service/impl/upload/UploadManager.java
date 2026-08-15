package com.stcloud.core.service.impl.upload;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上传状态机与配额管理器（TASK-002）。
 * 定义状态流转：INIT -> UPLOADING -> MERGING -> COMPLETED；异常 -> FAILED；中止 -> 恢复版本或删除。
 * 负责配额检查、合并原子认领、失败/中止回滚策略，供 UploadServiceImpl 编排调用。
 */
@Slf4j
@Component
public class UploadManager {

    @Resource
    private FileNodeMapper fileNodeMapper;

    @Resource
    private UserQuotaMapper userQuotaMapper;

    @Resource
    private TeamStorageMapper teamStorageMapper;

    @Resource
    private VersionService versionService;

    /** 上传容量检查：团队空间走团队配额，否则走个人配额；delta<=0 跳过 */
    public void checkQuotaForUpload(Long userId, Long spaceId, long delta) {
        if (delta <= 0) {
            return;
        }
        if (spaceId != null && spaceId > 0) {
            checkTeamQuota(spaceId, delta);
        } else {
            checkQuota(userId, delta);
        }
    }

    /**
     * 原子扣减配额（TASK-003）：并发安全，取代「读 used -> 校验 -> 更新」。
     * 由 Mapper 的条件 UPDATE 保证 used+delta 不超 quota 且不为负；正向扣减返回 0 行即超限抛异常。
     * 负向（释放）返回 0 行视为数据异常，静默忽略（不抛错，避免误拦释放）。
     */
    public void consumeQuota(Long userId, Long spaceId, long delta) {
        if (delta == 0) {
            return;
        }
        int rows;
        if (spaceId != null && spaceId > 0) {
            rows = teamStorageMapper.updateTeamStorageUsed(spaceId, delta);
        } else {
            rows = userQuotaMapper.updateStorageUsed(userId, delta);
        }
        if (rows <= 0 && delta > 0) {
            throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
        }
    }
    private void checkQuota(Long userId, long fileSize) {
        StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
    }

    private void checkTeamQuota(Long spaceId, long fileSize) {
        StorageInfoVO quota = teamStorageMapper.getTeamSpaceQuota(spaceId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
            }
        }
    }

    /**
     * 原子认领合并：仅当 upload_status=上传中(1) 时置为合并中(4)。
     * 成功=1 表示本请求取得合并权；失败=0 表示已被他人认领或已终态，调用方需重读节点判断（幂等）。
     */
    public boolean claimMerging(Long nodeId) {
        return fileNodeMapper.claimMerging(nodeId) > 0;
    }

    /** 合并成功后置为已完成(STORED) */
    public void markCompleted(FileNode node) {
        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
    }

    /**
     * 合并失败处理：
     * - 替换上传：清理 S3 残留并恢复上一版本（COMPLETED），保证既有文件可用；
     * - 新建上传：仅标记 FAILED，保留节点与分片，供客户端断点续传/重试 merge。
     */
    public void handleMergeFailure(FileNode node, boolean isReplaceUpload) {
        if (isReplaceUpload) {
            FileVersion latest = versionService.getLatestVersion(node.getId());
            if (latest != null) {
                node.setStoragePath(latest.getStoragePath());
                node.setFileMd5(latest.getFileMd5());
                node.setFileSize(latest.getFileSize());
                node.setUploadStatus(UploadStatus.COMPLETED.getCode());
            } else {
                node.setUploadStatus(UploadStatus.FAILED.getCode());
            }
            fileNodeMapper.updateById(node);
            return;
        }
        // 新建上传：不删除节点，标记失败供恢复
        fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()
                .eq(FileNode::getId, node.getId())
                .set(FileNode::getUploadStatus, UploadStatus.FAILED.getCode()));
    }

    /**
     * 中止/失败清理时回滚节点：
     * - 有历史版本（替换上传）-> 恢复上一版本并置 COMPLETED，返回 false；
     * - 无历史版本（新建上传）-> 删除节点（物理删除 pending 节点），返回 true。
     */
    public boolean rollbackUploadNode(FileNode node) {
        FileVersion latest = versionService.getLatestVersion(node.getId());
        if (latest != null) {
            node.setStoragePath(latest.getStoragePath());
            node.setFileMd5(latest.getFileMd5());
            node.setFileSize(latest.getFileSize());
            node.setUploadStatus(UploadStatus.COMPLETED.getCode());
            fileNodeMapper.updateById(node);
            return false;
        }
        fileNodeMapper.deleteById(node.getId());
        return true;
    }
}