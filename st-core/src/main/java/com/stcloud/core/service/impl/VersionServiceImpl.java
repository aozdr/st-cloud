package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileVersionVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.stcloud.core.event.FileIndexEvent;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VersionServiceImpl implements VersionService {

    @Resource
    private FileVersionMapper fileVersionMapper;
    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FileService fileService;
    @Resource
    private ApplicationEventPublisher eventPublisher;
    @Resource
    private com.stcloud.core.mapper.UserQuotaMapper userQuotaMapper;
    @Resource
    private com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;

    @Override
    public List<FileVersionVO> listVersions(Long fileNodeId) {
        FileNode node = fileService.getNodeByIdAndOwner(fileNodeId);
        if (!node.isFile()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅文件支持版本历史");
        }
        List<FileVersion> versions = fileVersionMapper.selectList(
                new LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileNodeId, fileNodeId)
                        .orderByDesc(FileVersion::getVersionNum));
        Integer maxNum = versions.isEmpty() ? null : versions.get(0).getVersionNum();
        return versions.stream().map(v -> toVO(v, maxNum)).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FileNode restoreVersion(Long fileNodeId, Long versionId) {
        FileNode node = fileService.getNodeByIdAndOwner(fileNodeId);
        if (!node.isFile()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅文件支持版本恢复");
        }
        FileVersion target = fileVersionMapper.selectById(versionId);
        if (target == null || !fileNodeId.equals(target.getFileNodeId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "版本不存在");
        }
        // 计算恢复前后大小差值，据此校验与调整配额
        long oldSize = node.getFileSize() == null ? 0 : node.getFileSize();
        long newSize = target.getFileSize() == null ? 0 : target.getFileSize();
        long delta = newSize - oldSize;
        String oldMd5 = node.getFileMd5();

        // 恢复到更大版本时需校验配额（恢复到更小版本只会退还，无需校验）
        if (delta > 0) {
            boolean isTeam = node.getSpaceId() != null && node.getSpaceId() > 0;
            if (isTeam) {
                com.stcloud.core.dto.StorageInfoVO tq = teamStorageMapper.getTeamSpaceQuota(node.getSpaceId());
                if (tq != null && tq.getQuota() != null && tq.getQuota() > 0) {
                    long used = tq.getUsed() == null ? 0 : tq.getUsed();
                    if (used + delta > tq.getQuota()) {
                        throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
                    }
                }
            } else {
                com.stcloud.core.dto.StorageInfoVO uq = userQuotaMapper.getUserQuota(node.getOwnerId());
                if (uq != null && uq.getQuota() != null && uq.getQuota() > 0) {
                    long used = uq.getUsed() == null ? 0 : uq.getUsed();
                    if (used + delta > uq.getQuota()) {
                        throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
                    }
                }
            }
            cloudStorageService.checkCapacity(delta);
        }

        // 恢复：将节点内容指向目标版本，并生成新版本记录（不覆盖目标版本）
        node.setStoragePath(target.getStoragePath());
        node.setFileMd5(target.getFileMd5());
        node.setFileSize(target.getFileSize());
        fileNodeMapper.updateById(node);

        // 按归属调整配额（大小不变时 delta=0 不影响）
        if (delta != 0) {
            if (node.getSpaceId() != null && node.getSpaceId() > 0) {
                teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta);
            } else {
                userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            }
        }

        // 同步引用计数：节点 MD5 变更后，旧 MD5 组与新 MD5 组都需重算
        if (oldMd5 != null) {
            fileNodeMapper.syncRefCountByMd5(oldMd5);
        }
        if (target.getFileMd5() != null && !target.getFileMd5().equals(oldMd5)) {
            fileNodeMapper.syncRefCountByMd5(target.getFileMd5());
        }

        snapshotCurrentVersion(node);

        // 恢复版本后重新索引到 ES（文件内容已变更）
        eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX));
        log.info("恢复文件版本: nodeId={}, versionId={}, delta={}", fileNodeId, versionId, delta);
        return node;
    }

    @Override
    public void snapshotCurrentVersion(FileNode node) {
        if (node == null || node.getId() == null) {
            return;
        }
        FileVersion latest = getLatestVersion(node.getId());
        int nextNum = (latest == null ? 0 : latest.getVersionNum()) + 1;

        FileVersion version = new FileVersion();
        version.setTenantId(node.getTenantId());
        version.setFileNodeId(node.getId());
        version.setVersionNum(nextNum);
        version.setFileSize(node.getFileSize());
        version.setFileMd5(node.getFileMd5());
        version.setStoragePath(node.getStoragePath());
        version.setModifierId(UserContext.getUserId());
        version.setModifierName(UserContext.getUsername());
        version.setCreatedAt(LocalDateTime.now());
        fileVersionMapper.insert(version);
    }

    @Override
    public FileVersion getLatestVersion(Long fileNodeId) {
        List<FileVersion> list = fileVersionMapper.selectList(
                new LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileNodeId, fileNodeId)
                        .orderByDesc(FileVersion::getVersionNum)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private FileVersionVO toVO(FileVersion v, Integer maxVersionNum) {
        FileVersionVO vo = new FileVersionVO();
        vo.setId(v.getId());
        vo.setFileNodeId(v.getFileNodeId());
        vo.setVersionNum(v.getVersionNum());
        vo.setFileSize(v.getFileSize());
        vo.setFileMd5(v.getFileMd5());
        vo.setModifierId(v.getModifierId());
        vo.setModifierName(v.getModifierName());
        vo.setCreatedAt(v.getCreatedAt());
        vo.setCurrent(maxVersionNum != null && maxVersionNum.equals(v.getVersionNum()));
        return vo;
    }
}