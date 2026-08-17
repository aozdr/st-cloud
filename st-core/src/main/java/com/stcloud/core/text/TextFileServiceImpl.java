package com.stcloud.core.text;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

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
    @Resource
    private UploadCommitManager uploadCommitManager;
    @Resource
    private UploadStorageManager uploadStorageManager;

    @Override
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

        // 4. 去重预查（事务外）：同租户同 md5 复用对象，否则上传新物理对象（S3 在事务外，与 OnlyOffice 保存一致）
        String md5 = DigestUtil.md5Hex(new ByteArrayInputStream(content));
        Long tenantId = node.getTenantId();
        String contentType = node.getContentType() != null ? node.getContentType() : "text/plain; charset=utf-8";
        FileObject existing = fileObjectService.findByTenantAndMd5(tenantId, md5);
        String storagePath;
        boolean uploadedNew = false;
        if (existing != null) {
            storagePath = existing.getStoragePath();
        } else {
            storagePath = tenantId + "/" + md5;
            storageService.uploadObject(storagePath, new ByteArrayInputStream(content), newSize, contentType);
            uploadedNew = true;
        }

        try {
            // 5. 事务内落库：对象归属 + 节点更新（@Version 乐观锁）+ 差值配额 + 事件
            uploadCommitManager.commitTextOverwrite(node, md5, newSize, storagePath, delta);
        } catch (RuntimeException e) {
            // 6. 事务失败清理：仅当本次实际上传过新对象且无记录/引用归零时才删除，避免误删并发复用对象
            if (uploadedNew) {
                cleanupOrphanUpload(tenantId, md5, storagePath);
            }
            throw e;
        }
        log.info("文本内容保存成功: nodeId={}, size={}", nodeId, newSize);
    }

    /**
     * 文本覆盖事务失败后的孤儿对象清理（F5，与简单上传口径一致）：
     * 仅当当前无对象记录（本次 insertIgnore 已随事务回滚）或记录引用归零且路径一致时才删除物理对象；
     * 删除失败不阻断主流程，交由定时任务兜底。
     */
    private void cleanupOrphanUpload(Long tenantId, String md5, String storagePath) {
        try {
            FileObject current = fileObjectService.findByTenantAndMd5(tenantId, md5);
            boolean noRecord = current == null;
            boolean unreferenced = current != null
                    && current.getRefCount() != null && current.getRefCount() <= 0
                    && storagePath.equals(current.getStoragePath());
            if (noRecord || unreferenced) {
                uploadStorageManager.deleteObjectQuietly(storagePath);
                log.warn("已尽力清理文本覆盖失败产生的孤儿对象: md5={}, storagePath={}", md5, storagePath);
            }
        } catch (Exception e) {
            // 清理失败不阻断主流程，交由定时任务兜底
            log.warn("文本覆盖失败清理孤儿对象异常（交由定时任务兜底）: md5={}", md5, e);
        }
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
