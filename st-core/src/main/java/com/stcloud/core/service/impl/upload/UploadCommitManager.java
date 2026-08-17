package com.stcloud.core.service.impl.upload;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.UploadCheckRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.VersionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传 DB 落库事务协作 bean（事务边界治理 F1-3 / F2-1 / F2-2）。
 * <p>
 * 职责：承接上传完成后"必须在一个数据库事务内完成"的写操作——秒传创建、简单上传落库、分片合并落库。
 * S3/外部网络调用全部在事务外执行；本类方法经由 Spring 代理（跨 bean 调用）保证 @Transactional 生效，
 * 事务回滚时由调用方按"引用归零才删除"规则尽力清理本次上传的物理对象。
 * <p>
 * 第二迭代（F5）：文本覆盖 / OnlyOffice 保存回调 / 在线解压的事务内落库同样收敛到本类，
 * 下载、S3 上传等网络调用均由调用方在事务外完成。
 */
@Component
public class UploadCommitManager {

    /** 引用计数：新建文件对 file_object 的初始单引用（去重对象引用 +1） */
    private static final int REF_COUNT_INITIAL = 1;
    /** 引用计数：文件夹不引用物理对象 */
    private static final int REF_COUNT_NONE = 0;
    /** 编辑器保存状态：6=关闭并保存（生成版本 source=1） */
    private static final int STATUS_CLOSED = 6;
    /** 编辑器保存状态：7=强制保存（同关闭保存） */
    private static final int STATUS_FORCE_SAVE = 7;

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
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private TeamStorageMapper teamStorageMapper;
    @Resource
    private ReliableEventPublisher reliableEventPublisher;

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

    /**
     * 文本覆盖落库（F5）：事务内完成 acquireByPath（对象归属） + 节点更新（@Version 乐观锁）
     * + 旧对象引用释放 + 差值配额 + 事件。
     * 调用方已事务外完成去重预查与 S3 上传；本事务回滚后由调用方按引用归零规则尽力清理本次上传对象。
     *
     * @param node        目标节点（调用方在事务外读取；本方法内按 @Version 乐观锁更新）
     * @param md5         新内容 MD5
     * @param newSize     新内容大小
     * @param storagePath 新对象存储路径（已上传或已存在的去重对象路径）
     * @param delta       新旧大小差值（增大>0 / 减小<0 / 相等=0）
     */
    @Transactional
    public void commitTextOverwrite(FileNode node, String md5, long newSize, String storagePath, long delta) {
        // 去重归属：同租户同 md5 已存在则复用（引用 +1），否则以本次上传路径创建对象记录
        FileObject object = fileObjectService.acquireByPath(node.getTenantId(), md5, newSize, storagePath);
        if (object == null) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, "文本内容落盘失败");
        }
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
            // 旧对象引用释放（保留物理对象，可能仍被版本历史引用）
            fileObjectService.release(oldObjectId);
        }
        // 配额差值记账（增大失败即回滚）
        if (delta != 0) {
            int q = node.getSpaceId() != null && node.getSpaceId() > 0
                    ? teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta)
                    : userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            if (q <= 0 && delta > 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
        // 事件：索引更新 + 同步变更
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.UPDATE);
    }

    /**
     * OnlyOffice 保存回调落库（F5）：事务内完成 acquireByPath（对象归属） + 节点更新 + 旧对象引用释放
     * + 差值配额 + 事件 + （关闭/强制保存时）版本快照与裁剪。
     * 调用方已事务外完成回调内容下载、去重预查与 S3 上传；本事务回滚后由调用方尽力清理本次上传对象。
     *
     * @param editorVersionLimit 编辑器版本保留上限（来自 EditorProperties，由调用方传入）
     */
    @Transactional
    public void commitEditorSave(FileNode node, Integer status, String md5, long newSize,
                                 String storagePath, long delta, int editorVersionLimit) {
        // 去重归属：同租户同 md5 已存在则复用（引用 +1），否则以本次上传路径创建对象记录
        FileObject object = fileObjectService.acquireByPath(node.getTenantId(), md5, newSize, storagePath);
        if (object == null) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, "回调内容落盘失败");
        }
        Long oldObjectId = node.getObjectId();
        node.setStoragePath(object.getStoragePath());
        node.setObjectId(object.getId());
        node.setFileMd5(md5);
        node.setFileSize(newSize);
        node.setUpdatedAt(LocalDateTime.now());
        int rows = fileNodeMapper.updateById(node);
        if (rows <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "文件已被其他操作更新，保存失败");
        }
        if (oldObjectId != null && !oldObjectId.equals(object.getId())) {
            // 旧对象引用释放（保留物理对象，可能仍被版本历史引用）
            fileObjectService.release(oldObjectId);
        }
        // 配额差值记账（增大失败即回滚）
        if (delta != 0) {
            int q = node.getSpaceId() != null && node.getSpaceId() > 0
                    ? teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), delta)
                    : userQuotaMapper.updateStorageUsed(node.getOwnerId(), delta);
            if (q <= 0 && delta > 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
        // 事件：索引更新 + 同步变更
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.UPDATE);
        // 关闭/强制保存：生成版本（source=1）+ 上限裁剪（编辑标记移除由调用方在事务外完成）
        if (status != null && (status == STATUS_CLOSED || status == STATUS_FORCE_SAVE)) {
            versionService.snapshotCurrentVersion(node, 1);
            versionService.pruneEditorVersions(node.getId(), editorVersionLimit);
        }
    }

    /**
     * 在线解压落库（F5）：一个事务内插入文件夹/文件节点 + 原子扣减配额 + 引用校正。
     * 调用方已事务外完成 ZIP 下载、逐条目去重预查与 S3 上传；本事务回滚后
     * 由调用方按引用归零规则尽力清理本次上传对象。
     *
     * @param entries 待落库条目元数据列表（按 ZIP 流顺序），每个元素为 Map，键说明见 ArchiveServiceImpl：
     *                zipPath（ZIP 内路径）/ directory（是否目录）/ size（文件字节数）/ md5 / storagePath /
     *                contentType / suffix（目录项为 null）
     * @return 落库文件数（进度回调按此计数）
     */
    @Transactional
    public int commitExtract(Long userId, Long tenantId, Long targetFolderId, String targetFolderPath,
                             List<Map<String, Object>> entries) {
        // ZIP 内路径 -> file_node ID 的映射，用于构建嵌套文件夹结构
        Map<String, Long> folderMap = new HashMap<>();
        folderMap.put("", targetFolderId);
        // ZIP 内路径 -> file_node path 的映射，用于产物路径拼接
        Map<String, String> folderPathMap = new HashMap<>();
        folderPathMap.put("", targetFolderPath);

        int count = 0;
        for (Map<String, Object> item : entries) {
            String zipPath = (String) item.get("zipPath");
            String[] parts = zipPath.split("/");
            // 逐级创建父文件夹
            String parentZipPath = "";
            for (int i = 0; i < parts.length - 1; i++) {
                String folderZipPath = parentZipPath.isEmpty() ? parts[i] : parentZipPath + "/" + parts[i];
                if (!folderMap.containsKey(folderZipPath)) {
                    Long parentFolderId = folderMap.get(parentZipPath);
                    String parentPath = folderPathMap.get(parentZipPath);
                    String newPath = "/".equals(parentPath) ? "/" + parts[i] : parentPath + "/" + parts[i];
                    Long newFolderId = createFolderNode(parts[i], parentFolderId, newPath, userId, tenantId);
                    folderMap.put(folderZipPath, newFolderId);
                    folderPathMap.put(folderZipPath, newPath);
                }
                parentZipPath = folderZipPath;
            }
            // 目录项：父目录已在上述循环创建，空目录由该循环兜底创建，无需额外处理
            if (Boolean.TRUE.equals(item.get("directory"))) {
                continue;
            }

            Long parentFolderId = folderMap.getOrDefault(parentZipPath, targetFolderId);
            String parentPath = folderPathMap.getOrDefault(parentZipPath, targetFolderPath);
            String fileName = parts[parts.length - 1];
            String filePath = "/".equals(parentPath) ? "/" + fileName : parentPath + "/" + fileName;
            long size = ((Number) item.get("size")).longValue();
            String md5 = (String) item.get("md5");
            String storagePath = (String) item.get("storagePath");
            String contentType = (String) item.get("contentType");
            String suffix = (String) item.get("suffix");

            // 原子扣减配额（并发超配额时 UPDATE 返回 0，抛异常回滚本次事务；预检保证常规场景不触发）
            if (size > 0 && userQuotaMapper.updateStorageUsed(userId, size) <= 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
            // 去重归属：同租户同 md5 已存在则复用（引用 +1），否则以本次上传路径创建对象记录
            FileObject object = fileObjectService.acquireByPath(tenantId, md5, size, storagePath);
            if (object == null) {
                throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
            }
            createFileNode(fileName, suffix, object.getStoragePath(), size, md5, object.getId(),
                    parentFolderId, filePath, userId, tenantId, contentType);
            // 保持 file_node.ref_count 与对象引用一致（同 md5 节点数）
            fileNodeMapper.syncRefCountByMd5(md5);
            count++;
        }
        return count;
    }

    /** 解压产物文件夹节点（不引用物理对象） */
    private Long createFolderNode(String name, Long parentId, String path, Long userId, Long tenantId) {
        FileNode folder = new FileNode();
        folder.setTenantId(tenantId);
        folder.setParentId(parentId);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setName(name);
        folder.setPath(path);
        folder.setStatus(NodeStatus.NORMAL.getCode());
        folder.setOwnerId(userId);
        folder.setUploaderId(userId);
        folder.setRefCount(REF_COUNT_NONE);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        return folder.getId();
    }

    /** 解压产物文件节点（引用计数由 syncRefCountByMd5 校正为同 md5 节点数） */
    private void createFileNode(String name, String suffix, String storagePath, Long size,
                                String fileMd5, Long objectId,
                                Long parentId, String path, Long userId, Long tenantId,
                                String contentType) {
        FileNode file = new FileNode();
        file.setTenantId(tenantId);
        file.setParentId(parentId);
        file.setNodeType(NodeType.FILE.getCode());
        file.setName(name);
        file.setPath(path);
        file.setFileSize(size);
        file.setFileMd5(fileMd5);
        file.setObjectId(objectId);
        file.setSuffix(suffix);
        file.setContentType(contentType);
        file.setStoragePath(storagePath);
        file.setStatus(NodeStatus.NORMAL.getCode());
        file.setUploadStatus(UploadStatus.COMPLETED.getCode());
        file.setOwnerId(userId);
        file.setUploaderId(userId);
        file.setRefCount(REF_COUNT_INITIAL);
        file.setVersion(0);
        fileNodeMapper.insert(file);
    }
}
