package com.stcloud.core.service.impl.upload;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadCheckRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传 DB 落库事务协作 bean（事务边界治理 F1-3 / F2-1 / F2-2）。
 * <p>
 * 职责：承接上传完成后"必须在一个数据库事务内完成"的写操作——秒传创建、简单上传落库、分片合并落库。
 * S3/外部网络调用全部在事务外执行；本类方法经由 Spring 代理（跨 bean 调用）保证 @Transactional 生效，
 * 事务回滚时由调用方按"引用归零才删除"规则尽力清理本次上传的物理对象。
 */
@Component
public class UploadCommitManager {

    /** 引用计数：新建文件对 file_object 的初始单引用（去重对象引用 +1） */
    private static final int REF_COUNT_INITIAL = 1;

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FileObjectService fileObjectService;
    @Resource
    private FileService fileService;
    @Resource
    private VersionService versionService;
    @Resource
    private UploadChunkManager chunkManager;
    @Resource
    private UploadManager uploadManager;
    @Resource
    private UploadEventPublisher uploadEventPublisher;

    /**
     * 秒传命中创建（F1-3）：事务内完成 对象引用+1 + 节点插入 + 扣配额 + 发事件。
     * 只读检查（配额/容量/父路径/重名）已在调用方事务外完成，此处仅收口 DB 写。
     */
    @Transactional
    public FileNode createInstantNode(Long userId, Long tenantId, UploadCheckRequest request,
                                      String parentPath, String fileName, String storagePath, String contentType) {
        // 去重归属：复用同租户同 md5 对象并 +1 引用，不重复上传物理对象
        FileObject object = fileObjectService.acquireByPath(tenantId, request.getFileMd5(),
                request.getFileSize(), storagePath);
        FileNode node = new FileNode();
        node.setParentId(request.getParentId());
        node.setNodeType(NodeType.FILE.getCode());
        node.setName(fileName);
        node.setPath(parentPath + "/" + fileName);
        node.setFileSize(request.getFileSize());
        node.setFileMd5(request.getFileMd5());
        node.setContentType(contentType);
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
        uploadManager.consumeQuota(userId, request.getSpaceId(), request.getFileSize());
        return node;
    }

    /**
     * 简单上传落库（F2-1）：事务内完成 acquireByPath（对象记录/引用） + 节点插入 + 扣配额 + 发事件。
     * 调用方已事务外完成 MD5 计算、配额/容量预检与 S3 限速上传；
     * 本事务回滚后由调用方校验 ref_count 并尽力清理本次上传对象。
     */
    @Transactional
    public FileNodeVO commitSimpleUpload(Long userId, Long tenantId, Long spaceId, Long parentId,
                                         String parentPath, String fileName, String md5, long fileSize,
                                         String storagePath, String contentType) {
        // 去重归属：同租户同 md5 已存在则复用（引用 +1），否则以本次上传路径创建对象记录
        FileObject object = fileObjectService.acquireByPath(tenantId, md5, fileSize, storagePath);
        FileNode node = new FileNode();
        node.setParentId(parentId);
        node.setNodeType(NodeType.FILE.getCode());
        node.setName(fileName);
        node.setPath(parentPath + "/" + fileName);
        node.setFileSize(fileSize);
        node.setFileMd5(md5);
        node.setContentType(contentType);
        node.setSuffix(fileService.extractSuffix(fileName));
        node.setStoragePath(object.getStoragePath());
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

    /**
     * 分片合并落库（F2-2）：事务内完成 markChunksMerged + acquireByPath（对象归属） + 节点更新
     * + 版本快照 + 差值配额 + 事件。
     * S3 completeMultipart / abort / deleteObjectQuietly 均已移出事务；
     * 去重命中产生的临时合并对象由调用方在本事务提交后按引用归零规则尽力清理。
     *
     * @param originalSize 替换上传时的原文件大小（新建上传为 null），用于差值计费
     */
    @Transactional
    public FileNodeVO finalizeMerge(FileNode node, String uploadId, Long originalSize) {
        // 合并成功：标记分片已合并（保留记录以支撑重复 merge 幂等）
        chunkManager.markChunksMerged(uploadId);

        Long oldObjectId = node.getObjectId();
        Long objectTenant = node.getTenantId() != null ? node.getTenantId() : UserContext.getTenantId();
        long objectSize = node.getFileSize() == null ? 0L : node.getFileSize();
        // 去重归属对象：合并完成后按 md5 归属 file_object；同 md5 已存在则复用
        FileObject object = fileObjectService.acquireByPath(objectTenant, node.getFileMd5(),
                objectSize, node.getStoragePath());
        if (object != null) {
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
        long original = originalSize == null ? 0 : originalSize;
        long delta = newSize - original;
        uploadManager.consumeQuota(node.getOwnerId(), node.getSpaceId(), delta);
        return fileService.toVO(node);
    }
}
