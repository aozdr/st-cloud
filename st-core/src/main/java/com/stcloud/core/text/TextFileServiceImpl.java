package com.stcloud.core.text;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

/**
 * 文本内容保存实现：与 OnlyOffice 保存回调落库口径一致（去重/配额/事件），
 * 不含编辑锁与版本快照（轻量文本编辑按覆盖保存处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextFileServiceImpl implements TextFileService {

    /** 文本编辑内容大小上限：2MB（UTF-8 字节） */
    private static final long MAX_TEXT_SIZE = 2L * 1024 * 1024;

    private final FileNodeMapper fileNodeMapper;
    private final FileObjectService fileObjectService;
    private final StorageService storageService;
    private final CloudStorageService cloudStorageService;
    private final UserQuotaMapper userQuotaMapper;
    private final TeamStorageMapper teamStorageMapper;
    private final ReliableEventPublisher reliableEventPublisher;

    @Override
    @Transactional
    public void overwriteContent(Long nodeId, byte[] content) {
        // 1. 内容校验
        if (content == null || content.length <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "内容不能为空");
        }
        if (content.length > MAX_TEXT_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "文本内容超出大小限制（2MB）");
        }
        // 2. 节点校验：存在、文件、正常、已完成
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (node.isFolder()
                || node.getUploadStatus() == null || node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅已完成文件可编辑内容");
        }

        // 3. 配额/容量预检（仅增大时；并发超配额由第 7 步原子扣减兜底）
        long newSize = content.length;
        long oldSize = node.getFileSize() == null ? 0 : node.getFileSize();
        long delta = newSize - oldSize;
        if (delta > 0) {
            checkQuotaBeforeWrite(node, delta);
            cloudStorageService.checkCapacity(delta);
        }

        // 4. 去重落盘：同租户同 md5 复用对象，否则上传新物理对象（与 OnlyOffice 保存一致）
        String md5 = DigestUtil.md5Hex(new ByteArrayInputStream(content));
        Long tenantId = node.getTenantId();
        String contentType = node.getContentType() != null ? node.getContentType() : "text/plain; charset=utf-8";
        FileObject object = fileObjectService.acquire(tenantId, md5, newSize, () -> {
            String key = tenantId + "/" + md5;
            storageService.uploadObject(key, new ByteArrayInputStream(content), newSize, contentType);
            return key;
        });
        if (object == null) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, "文本内容落盘失败");
        }

        // 5. 更新节点（@Version 乐观锁：并发修改时 rows<=0 抛冲突）
        Long oldObjectId = node.getObjectId();
        node.setStoragePath(object.getStoragePath());
        node.setObjectId(object.getId());
        node.setFileMd5(md5);
        node.setFileSize(newSize);
        node.setUpdatedAt(LocalDateTime.now());
        int rows = fileNodeMapper.updateById(node);
        if (rows <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "文件已被其他操作更新，请重试");
        }
        if (oldObjectId != null && !oldObjectId.equals(object.getId())) {
            fileObjectService.release(oldObjectId);
        }

        // 6. 配额差值记账（增大失败即回滚）
        if (delta != 0) {
            int q = node.getSpaceId() != null && node.getSpaceId() > 0
                    ? teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta)
                    : userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            if (q <= 0 && delta > 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }

        // 7. 事件：索引更新 + 同步变更
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.UPDATE);
        log.info("文本内容保存成功: nodeId={}, size={}", nodeId, newSize);
    }

    /** 增大写入前的配额预检（与版本恢复/上传口径一致） */
    private void checkQuotaBeforeWrite(FileNode node, long delta) {
        if (node.getSpaceId() != null && node.getSpaceId() > 0) {
            StorageInfoVO q = teamStorageMapper.getTeamSpaceQuota(node.getSpaceId());
            if (q != null && q.getQuota() != null && q.getQuota() > 0) {
                long used = q.getUsed() == null ? 0 : q.getUsed();
                if (used + delta > q.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
                }
            }
        } else {
            StorageInfoVO q = userQuotaMapper.getUserQuota(node.getOwnerId());
            if (q != null && q.getQuota() != null && q.getQuota() > 0) {
                long used = q.getUsed() == null ? 0 : q.getUsed();
                if (used + delta > q.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
                }
            }
        }
    }
}
