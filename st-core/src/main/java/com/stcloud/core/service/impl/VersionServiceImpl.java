package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileVersionVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.editor.EditorLockService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
    private FileObjectService fileObjectService;
    @Resource
    private ReliableEventPublisher reliableEventPublisher;
    @Resource
    private com.stcloud.core.mapper.UserQuotaMapper userQuotaMapper;
    @Resource
    private com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;
    /** 编辑保护锁服务：生产必有；测试上下文手工装配时缺失，保护检查跳过（保持既有测试兼容） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EditorLockService editorLockService;

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
        // 编辑保护：文件正在编辑时禁止版本恢复（TC-19，D3 决策）
        if (editorLockService != null) {
            editorLockService.assertNotEditing(Collections.singletonList(fileNodeId));
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
        // 恢复到更大版本时并发超配额 -> 条件更新返回 0，抛异常回滚（恢复更小版本仅退还，0 行忽略）
        if (delta != 0) {
            int rows;
            if (node.getSpaceId() != null && node.getSpaceId() > 0) {
                rows = teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta);
            } else {
                rows = userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            }
            if (rows <= 0 && delta > 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }

        // 对象引用：旧对象释放（保留物理对象，可能仍被版本历史引用）；目标 md5 有对象则复用 +1，旧数据回退指向版本历史路径
        Long oldObjectId = node.getObjectId();
        Long tenantId = node.getTenantId() != null ? node.getTenantId() : UserContext.getTenantId();
        FileObject targetObject = fileObjectService.findByTenantAndMd5(tenantId, target.getFileMd5());
        if (targetObject != null) {
            fileObjectService.acquire(tenantId, target.getFileMd5(), target.getFileSize(),
                    () -> targetObject.getStoragePath());
            node.setObjectId(targetObject.getId());
            node.setStoragePath(targetObject.getStoragePath());
        } else {
            // 版本历史路径未纳入对象体系：不创建对象，版本历史物理对象长期保留
            node.setObjectId(null);
            node.setStoragePath(target.getStoragePath());
        }
        if (oldObjectId != null && !oldObjectId.equals(node.getObjectId())) {
            fileObjectService.release(oldObjectId);
        }
        fileNodeMapper.updateById(node);

        snapshotCurrentVersion(node);

        // 恢复版本后重新索引到 ES（文件内容已变更）
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        log.info("恢复文件版本: nodeId={}, versionId={}, delta={}", fileNodeId, versionId, delta);
        return node;
    }

    @Override
    public void snapshotCurrentVersion(FileNode node) {
        snapshotCurrentVersion(node, 0);
    }

    @Override
    public void snapshotCurrentVersion(FileNode node, Integer source) {
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
        // 保存回调为匿名请求（OnlyOffice 服务端回调，无登录态），UserContext 可能为空；
        // modifier_id 兜底为文件 owner，避免 NOT NULL 约束失败导致保存 500（20260815 实测）
        Long modifierId = UserContext.getUserId();
        version.setModifierId(modifierId != null ? modifierId : node.getOwnerId());
        version.setModifierName(UserContext.getUsername());
        // 版本来源：0-上传覆盖 / 1-编辑器保存（D1：仅 source=1 参与 20 条上限裁剪）
        version.setSource(source != null ? source : 0);
        version.setCreatedAt(LocalDateTime.now());
        fileVersionMapper.insert(version);
    }

    @Override
    public void pruneEditorVersions(Long fileNodeId, int limit) {
        if (fileNodeId == null || limit <= 0) {
            return;
        }
        // 仅统计 source=1（编辑器保存）版本，source=0（上传覆盖）不受影响（D1）
        List<FileVersion> editorVersions = fileVersionMapper.selectList(
                new LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileNodeId, fileNodeId)
                        .eq(FileVersion::getSource, 1)
                        .orderByAsc(FileVersion::getVersionNum));
        if (editorVersions.size() <= limit) {
            return;
        }
        List<Long> toDelete = new ArrayList<>();
        for (int i = 0; i < editorVersions.size() - limit; i++) {
            toDelete.add(editorVersions.get(i).getId());
        }
        // 仅删除版本记录（元数据）；物理对象保守保留——版本可能与其他节点/版本共享对象，
        // ref_count 为节点级引用，误删会破坏版本回滚与去重（TC-17）
        fileVersionMapper.deleteBatchIds(toDelete);
        log.info("裁剪编辑器保存版本: fileNodeId={}, limit={}, deleted={}",
                fileNodeId, limit, toDelete.size());
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
