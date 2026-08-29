package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.cache.TtlCache;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.FolderSizeVO;
import com.stcloud.core.dto.FileTreeNodeVO;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.editor.EditorLockService;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;
    @Resource
    private ReliableEventPublisher reliableEventPublisher;
    @Resource
    private FileObjectService fileObjectService;
    /** 编辑保护锁服务：生产必有；测试上下文手工装配时缺失，保护检查跳过（保持既有测试兼容） */
    @Autowired(required = false)
    private EditorLockService editorLockService;

    /** 可访问性缓存（TASK-005）：key=acc:nodeId → 是否可访问；节点结构变更时显式失效；
     * 默认内存实现，开启 stcloud.cache.redis.enabled 后由 CacheFactory 切换为 Redis（TASK-003） */
    private Cache accessibleCache = new TtlCache(ACCESSIBLE_CACHE_TTL_MS);

    /** 缓存后端工厂（可选）：Redis 启用时切换缓存实现（TASK-003） */
    @Autowired(required = false)
    private CacheFactory cacheFactory;
    /** 可访问性缓存 TTL：30 秒，作为多实例/遗漏失效场景的最终一致性兜底 */
    private static final long ACCESSIBLE_CACHE_TTL_MS = 30_000;
    private static final String ACCESSIBLE_KEY_PREFIX = "acc:";

    private static final String INVALID_CHARS_REGEX = "[/\\\\:*?\"<>|]";
    private static final int MAX_NAME_LENGTH = 255;
    /** 引用计数：文件夹不引用任何物理对象 */
    private static final int REF_COUNT_NONE = 0;
    /** 引用计数：复制文件时对源 file_object 的初始引用 */
    private static final int REF_COUNT_INITIAL = 1;
    private static final int MAX_FOLDER_DEPTH = 20;

    // ==================== 目录管理 ====================

    @Override
    @Transactional
    public FileNodeVO createFolder(Long parentId, String folderName) {
        validateFileName(folderName);
        Long userId = UserContext.getUserId();
        String parentPath = validateAndGetParentPath(parentId);

        if (fileNodeMapper.countByParentAndName(parentId, folderName) > 0) {
            throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS);
        }

        if (parentId != 0) {
            int depth = parentPath.split("/").length;
            if (depth >= MAX_FOLDER_DEPTH) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                        "目录层级超过最大限制(" + MAX_FOLDER_DEPTH + "层)");
            }
        }

        FileNode folder = new FileNode();
        folder.setParentId(parentId);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setName(folderName);
        folder.setPath(parentPath + "/" + folderName);
        folder.setStatus(NodeStatus.NORMAL.getCode());
        folder.setUploadStatus(UploadStatus.COMPLETED.getCode());
        folder.setOwnerId(userId);
        folder.setUploaderId(userId);
        folder.setRefCount(REF_COUNT_NONE);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        reliableEventPublisher.publishFileIndex(folder, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(folder, SyncChangeEvent.ChangeType.CREATE);
        return toVO(folder);
    }

    @Override
    public IPage<FileNodeVO> listDirectory(Long parentId, int page, int size) {
        if (parentId != null && parentId != 0) {
            validateAccessible(parentId);
        }
        Long userId = UserContext.getUserId();
        Page<FileNode> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .eq(FileNode::getHidden, 0)
                // 个人目录：无条件只返回当前用户自己的文件（单一租户、无租户切换，不因 dataScope 放行他人文件）
                .eq(FileNode::getOwnerId, userId)
                .and(w -> w.eq(FileNode::getNodeType, NodeType.FOLDER.getCode())
                        .or().eq(FileNode::getUploadStatus, UploadStatus.COMPLETED.getCode()))
                .orderByDesc(FileNode::getNodeType)
                .orderByDesc(FileNode::getUpdatedAt);
        return fileNodeMapper.selectPage(pageParam, wrapper).convert(this::toVO);
    }

    /** 文件夹大小缓存 TTL：5 分钟（结构变更靠 TTL 兜底，不做全量精确失效） */
    private static final long FOLDER_SIZE_CACHE_TTL_MS = 300_000;
    private static final String FOLDER_SIZE_KEY_PREFIX = "fsize:";

    @Override
    public FolderSizeVO getFolderSize(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || !node.isNormal() || !node.isFolder()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        Long userId = UserContext.getUserId();
        // 个人文件夹大小：仅属主可查询；团队文件夹由团队权限前置校验
        if ((node.getSpaceId() == null || node.getSpaceId() <= 0) && !node.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        Cache cache = buildFolderSizeCache();
        String key = FOLDER_SIZE_KEY_PREFIX + nodeId;
        Object cached = cache.get(key);
        if (cached instanceof FolderSizeVO vo) {
            return vo;
        }
        FolderSizeVO vo = aggregateFolderSize(node);
        cache.put(key, vo);
        return vo;
    }

    /** 逐层 BFS 聚合子树大小与数量，避免深目录递归栈溢出 */
    private FolderSizeVO aggregateFolderSize(FileNode root) {
        FolderSizeVO vo = new FolderSizeVO();
        vo.setSize(0L);
        vo.setFileCount(0L);
        vo.setFolderCount(0L);
        java.util.Deque<Long> queue = new java.util.ArrayDeque<>();
        queue.add(root.getId());
        int processed = 0;
        while (!queue.isEmpty()) {
            Long parentId = queue.poll();
            List<FileNode> children = fileNodeMapper.selectList(new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, parentId)
                    .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                    .eq(FileNode::getUploadStatus, UploadStatus.COMPLETED.getCode()));
            for (FileNode child : children) {
                if (child.isFolder()) {
                    vo.setFolderCount(vo.getFolderCount() + 1);
                    queue.add(child.getId());
                } else {
                    vo.setFileCount(vo.getFileCount() + 1);
                    vo.setSize(vo.getSize() + (child.getFileSize() != null ? child.getFileSize() : 0L));
                }
            }
            // 防御上限：单次聚合最多遍历 50 万节点，防止异常超大目录拖垮 DB
            processed += children.size();
            if (processed > 500_000) {
                break;
            }
        }
        return vo;
    }

    private Cache buildFolderSizeCache() {
        if (cacheFactory != null) {
            return cacheFactory.create(FOLDER_SIZE_CACHE_TTL_MS);
        }
        return new TtlCache(FOLDER_SIZE_CACHE_TTL_MS);
    }

    @Override
    public List<FileNodeVO> searchFiles(String keyword) {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getOwnerId, userId)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .eq(FileNode::getHidden, 0)
                .like(FileNode::getName, keyword)
                .orderByDesc(FileNode::getNodeType)
                .orderByDesc(FileNode::getUpdatedAt)
                .last("LIMIT 50");
        return fileNodeMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FileNodeVO rename(Long nodeId, String newName) {
        validateFileName(newName);
        FileNode node = getNodeByIdAndOwner(nodeId);
        // 编辑保护：文件正在编辑时禁止重命名（TC-18）
        assertNotEditing(node);
        // 同名重命名：无实际变更，不发布 RENAME 同步日志（避免 old==new 脏日志导致客户端自擦状态）
        if (node.getName().equals(newName)) {
            return toVO(node);
        }
        String oldPath = node.getPath();
        String parentPath = oldPath.substring(0, oldPath.lastIndexOf("/"));

        LambdaQueryWrapper<FileNode> dupWrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, node.getParentId())
                .eq(FileNode::getName, newName)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .ne(FileNode::getId, nodeId);
        if (fileNodeMapper.selectCount(dupWrapper) > 0) {
            throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS);
        }

        String newPath = parentPath + "/" + newName;
        node.setName(newName);
        node.setPath(newPath);
        fileNodeMapper.updateById(node);

        if (node.isFolder()) {
            fileNodeMapper.updateChildrenPath(oldPath, newPath);
        }
        publishMetaUpdate(node, newPath);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.RENAME, oldPath);
        return toVO(node);
    }

    @Override
    @Transactional
    public void move(List<Long> nodeIds, Long targetParentId) {
        String targetPath = validateAndGetParentPath(targetParentId);
        for (Long nodeId : nodeIds) {
            FileNode node = getNodeByIdAndOwner(nodeId);
            // 编辑保护：文件正在编辑时禁止移动（TC-18）
            assertNotEditing(node);
            if (nodeId.equals(targetParentId)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "不能将文件移动到自身");
            }
            // 同目录移动：无实际变更，跳过（避免 old==new 的 MOVE 脏日志）
            if (targetParentId != null && targetParentId.equals(node.getParentId())) {
                continue;
            }
            if (targetParentId != 0) {
                FileNode target = getNodeByIdAndOwner(targetParentId);
                if (target.getPath().startsWith(node.getPath() + "/")) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "不能将文件夹移动到其子目录中");
                }
            }
            if (fileNodeMapper.countByParentAndName(targetParentId, node.getName()) > 0) {
                throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS.getCode(),
                        "目标目录已存在同名: " + node.getName());
            }
            String oldPath = node.getPath();
            String newPath = targetPath + "/" + node.getName();
            node.setParentId(targetParentId);
            node.setPath(newPath);
            fileNodeMapper.updateById(node);
            // 移动后祖先链变化：失效该节点可访问性缓存（子孙由 TTL 兜底）
            invalidateAccessible(nodeId);
            if (node.isFolder()) {
                fileNodeMapper.updateChildrenPath(oldPath, newPath);
            }
            publishMetaUpdate(node, newPath);
            reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.MOVE, oldPath);
        }
    }

    @Override
    @Transactional
    public void copy(List<Long> nodeIds, Long targetParentId) {
        String targetPath = validateAndGetParentPath(targetParentId);
        for (Long nodeId : nodeIds) {
            FileNode node = getNodeByIdAndOwner(nodeId);
            copyNodeRecursive(node, targetParentId, targetPath);
        }
    }

    private void copyNodeRecursive(FileNode source, Long targetParentId, String targetPath) {
        Long userId = UserContext.getUserId();
        String newName = resolveNameConflict(targetParentId, source.getName());
        String newPath = targetPath + "/" + newName;

        FileNode copy = new FileNode();
        copy.setParentId(targetParentId);
        copy.setNodeType(source.getNodeType());
        copy.setName(newName);
        copy.setPath(newPath);
        copy.setFileSize(source.getFileSize());
        copy.setFileMd5(source.getFileMd5());
        copy.setContentType(source.getContentType());
        copy.setSuffix(source.getSuffix());
        copy.setStoragePath(source.getStoragePath());
        copy.setStatus(NodeStatus.NORMAL.getCode());
        copy.setUploadStatus(UploadStatus.COMPLETED.getCode());
        copy.setOwnerId(userId);
        copy.setUploaderId(userId);
        copy.setRefCount(REF_COUNT_INITIAL);
        copy.setVersion(0);
        copy.setThumbnailPath(source.getThumbnailPath());
        // 文件复制：归属到源对象并 +1 引用（去重）；旧数据无 object_id 时回退 md5 重算
        if (source.isFile() && source.getFileMd5() != null && source.getObjectId() != null) {
            Long tenantId = source.getTenantId() != null ? source.getTenantId() : UserContext.getTenantId();
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            FileObject object = fileObjectService.acquire(tenantId, source.getFileMd5(), size,
                    () -> source.getStoragePath());
            if (object != null) {
                copy.setObjectId(object.getId());
                copy.setStoragePath(object.getStoragePath());
            }
        }
        fileNodeMapper.insert(copy);

        if (source.isFile() && source.getFileMd5() != null) {
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            checkUserQuota(userId, size);
            cloudStorageService.checkCapacity(size);
            // 旧数据（无 object_id）回退按 md5 重算引用
            if (copy.getObjectId() == null) {
                incrementRefCount(source.getFileMd5());
            }
            // 原子扣减：并发超配额时 update 返回 0，抛异常回滚本次复制（TASK-003）
            if (size > 0 && userQuotaMapper.updateStorageUsed(userId, size) <= 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
        } else if (source.isFolder()) {
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
            LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, source.getId())
                    .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
            List<FileNode> children = fileNodeMapper.selectList(wrapper);
            for (FileNode child : children) {
                copyNodeRecursive(child, copy.getId(), newPath);
            }
        }
    }

    private void publishMetaUpdate(FileNode node, String newPath) {
        LambdaQueryWrapper<FileNode> fileQuery = new LambdaQueryWrapper<FileNode>()
                .and(w -> w.eq(FileNode::getId, node.getId())
                        .or().likeRight(FileNode::getPath, newPath + "/"));
        List<FileNode> affectedFiles = fileNodeMapper.selectList(fileQuery);
        for (FileNode file : affectedFiles) {
            reliableEventPublisher.publishFileIndex(file, FileIndexEvent.ActionType.UPDATE_META);
        }
    }

    @Override
    @Transactional
    public void deleteToRecycleBin(List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            FileNode node = getNodeByIdAndOwner(nodeId);
            // 编辑保护：文件正在编辑时禁止删除（TC-18）
            assertNotEditing(node);
            if (node.getStatus() != NodeStatus.NORMAL.getCode()) {
                continue;
            }

            // 只置被删节点自身为回收态；子孙 status 不动，访问时由祖先链校验拦截
            node.setStatus(NodeStatus.RECYCLED.getCode());
            node.setUpdatedAt(LocalDateTime.now());
            fileNodeMapper.updateById(node);
            // 回收后该节点立即不可访问：失效可访问性缓存
            invalidateAccessible(nodeId);
            // ES/同步：仅移除被删节点自身（子孙由搜索侧祖先链过滤、同步端递归删除兜底）
            reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.DELETE);
            reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.DELETE);
            for (FileNode descendant : collectDescendants(nodeId)) {
                // 只改变被删文件夹自身状态：子孙不发事件（桌面端对文件夹 DELETE 递归删本地子树；
                // 搜索侧按祖先链过滤兜底）。此处仅级联失效可访问性缓存（内存操作，无 DB 写入），
                // 避免 TTL 窗口内缓存命中导致已回收子孙仍可访问
                invalidateAccessible(descendant.getId());
            }
        }
    }

    // ==================== 文件信息查询 ====================

    @Override
    public FileNodeVO getNodeDetail(Long nodeId) {
        FileNode node = getNodeByIdAndOwner(nodeId);
        validateAccessible(nodeId);
        return toVO(node);
    }

    @Override
    public List<FileTreeNodeVO> getFolderTree() {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getNodeType, NodeType.FOLDER.getCode())
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                // 个人目录树仅返回当前用户自己的文件夹，不因 dataScope（租户级用户）带上其他用户/管理员的文件夹。
                // 保存到云盘/移动/归档的目标目录必须落在当前用户自己的云盘内。
                .eq(FileNode::getOwnerId, userId)
                .and(w -> w.isNull(FileNode::getSpaceId).or().eq(FileNode::getSpaceId, 0))
                .orderByAsc(FileNode::getName);
        List<FileNode> folders = fileNodeMapper.selectList(wrapper);
        Map<Long, List<FileNode>> parentIdMap = folders.stream()
                .collect(Collectors.groupingBy(FileNode::getParentId));
        return buildTreeNode(parentIdMap, 0L);
    }

    private List<FileTreeNodeVO> buildTreeNode(Map<Long, List<FileNode>> parentIdMap, Long parentId) {
        List<FileNode> children = parentIdMap.get(parentId);
        if (children == null || children.isEmpty()) {
            return new ArrayList<>();
        }
        return children.stream().map(node -> {
            FileTreeNodeVO vo = new FileTreeNodeVO();
            vo.setId(node.getId());
            vo.setName(node.getName());
            vo.setPath(node.getPath());
            vo.setChildren(buildTreeNode(parentIdMap, node.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public StorageInfoVO getStorageInfo() {
        Long userId = UserContext.getUserId();
        StorageInfoVO info = userQuotaMapper.getUserQuota(userId);
        if (info == null) {
            info = new StorageInfoVO();
            info.setUsed(0L);
            info.setQuota(0L);
        }
        return info;
    }

    // ==================== 引用计数管理 ====================

    @Override
    public void incrementRefCount(String md5) {
        // 重算并同步该 MD5 下所有节点的引用计数为当前实际引用总数，
        // 保证新增引用（秒传/复制）后 ref_count 与真实引用数一致
        fileNodeMapper.syncRefCountByMd5(md5);
    }

    /** 校验个人存储配额 */
    private void checkUserQuota(Long userId, long fileSize) {
        StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
    }

    /** 校验团队空间存储配额 */
    private void checkTeamQuota(Long spaceId, long fileSize) {
        if (spaceId == null || spaceId <= 0) {
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

    void decrementRefCount(FileNode node) {
        if (node.getFileMd5() == null) return;
        int newRefCount = (node.getRefCount() == null ? 1 : node.getRefCount()) - 1;
        if (newRefCount <= 0) {
            // 引用计数归零，需要删除S3物理文件（由调用方处理）
        }
        node.setRefCount(Math.max(0, newRefCount));
        fileNodeMapper.updateById(node);
    }

    // ==================== 辅助方法 ====================

    /**
     * 编辑保护：节点（文件）或文件夹子孙中存在编辑标记时抛出 FILE_EDITING。
     * 覆盖删除/移动/重命名（含团队路径），与版本恢复、覆盖上传拦截口径一致（TC-18/19）。
     */
    private void assertNotEditing(FileNode node) {
        if (editorLockService == null) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        ids.add(node.getId());
        if (node.isFolder()) {
            for (FileNode descendant : collectDescendants(node.getId())) {
                ids.add(descendant.getId());
            }
        }
        editorLockService.assertNotEditing(ids);
    }

    void validateFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "名称超过最大长度");
        }
        if (name.matches(".*" + INVALID_CHARS_REGEX + ".*")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "名称包含非法字符");
        }
    }

    @Override
    public String validateAndGetParentPath(Long parentId) {
        if (parentId == null || parentId == 0) {
            return "";
        }
        FileNode parent = fileNodeMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "父文件夹不存在");
        }
        if (!parent.isFolder()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "目标不是文件夹");
        }
        if (parent.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_IN_RECYCLE);
        }
        validateAccessible(parentId);
        return parent.getPath();
    }

    @Override
    public FileNode getNodeByIdAndOwner(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        Long userId = UserContext.getUserId();
        // 个人文件（spaceId 空/0）：仅属主可操作，不因 dataScope 放行他人个人文件；
        // 团队文件（spaceId>0）由团队权限前置校验（TeamController），此处不拦截。
        if ((node.getSpaceId() == null || node.getSpaceId() <= 0) && !node.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return node;
    }

    @PostConstruct
    void initAccessibleCache() {
        // 仅当存在缓存工厂（Spring 容器）时按配置切换后端；单元测试直 new 时保持默认内存缓存
        if (cacheFactory != null) {
            accessibleCache = cacheFactory.create(ACCESSIBLE_CACHE_TTL_MS);
        }
    }

    @Override
    public void validateAccessible(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        // 命中缓存直接判定，避免重复执行祖先链递归 SQL（分享/下载/收藏等高频访问路径复用）
        String key = ACCESSIBLE_KEY_PREFIX + nodeId;
        Boolean cached = (Boolean) accessibleCache.get(key);
        if (cached != null) {
            if (!cached) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
            return;
        }
        boolean accessible = fileNodeMapper.countInaccessibleAncestors(nodeId) == 0;
        accessibleCache.put(key, accessible);
        if (!accessible) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    @Override
    public void invalidateAccessible(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        // 结构变更后清除该节点可访问性缓存，下次访问重新按祖先链计算
        accessibleCache.removeByPrefix(ACCESSIBLE_KEY_PREFIX + nodeId);
    }

    @Override
    public List<FileNode> collectDescendants(Long nodeId) {
        List<FileNode> result = new ArrayList<>();
        List<FileNode> children = fileNodeMapper.selectList(
                new LambdaQueryWrapper<FileNode>().eq(FileNode::getParentId, nodeId));
        for (FileNode child : children) {
            result.add(child);
            result.addAll(collectDescendants(child.getId()));
        }
        return result;
    }

    @Override
    public String resolveNameConflict(Long parentId, String name) {
        Long effectiveParentId = parentId == null ? 0L : parentId;
        if (fileNodeMapper.countByParentAndName(effectiveParentId, name) == 0) {
            return name;
        }
        return generateUniqueName(effectiveParentId, name);
    }

    private String generateUniqueName(Long parentId, String name) {
        String baseName;
        String ext = "";
        int dotIdx = name.lastIndexOf(".");
        if (dotIdx > 0) {
            baseName = name.substring(0, dotIdx);
            ext = name.substring(dotIdx);
        } else {
            baseName = name;
        }
        int suffix = 1;
        String newName;
        do {
            newName = baseName + "(" + suffix + ")" + ext;
            suffix++;
        } while (fileNodeMapper.countByParentAndName(parentId, newName) > 0);
        return newName;
    }

    @Override
    public String extractSuffix(String fileName) {
        if (fileName == null) return null;
        int dotIdx = fileName.lastIndexOf(".");
        return dotIdx > 0 ? fileName.substring(dotIdx + 1).toLowerCase() : null;
    }

    @Override
    public String guessContentType(String fileName) {
        String suffix = extractSuffix(fileName);
        if (suffix == null) return "application/octet-stream";
        return switch (suffix.toLowerCase()) {
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "doc", "docx" -> "application/msword";
            case "xls", "xlsx" -> "application/vnd.ms-excel";
            case "ppt", "pptx" -> "application/vnd.ms-powerpoint";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "mp4" -> "video/mp4";
            case "mp3" -> "audio/mpeg";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    @Override
    public FileNodeVO toVO(FileNode node) {
        FileNodeVO vo = new FileNodeVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setNodeType(node.getNodeType());
        vo.setName(node.getName());
        vo.setPath(node.getPath());
        vo.setFileSize(node.getFileSize());
        vo.setSuffix(node.getSuffix());
        vo.setContentType(node.getContentType());
        vo.setFileMd5(node.getFileMd5());
        vo.setStatus(node.getStatus());
        vo.setThumbnailPath(node.getThumbnailPath());
        vo.setCreatedAt(node.getCreatedAt());
        vo.setUpdatedAt(node.getUpdatedAt());
        // 锁定信息（P2 文件锁定）：直接透传实体锁定字段，个人/团队列表均走本方法
        vo.setLockedBy(node.getLockedBy());
        vo.setLockedAt(node.getLockedAt());
        vo.setLockExpireAt(node.getLockExpireAt());
        return vo;
    }

    // ==================== 团队空间文件操作 ====================

    @Override
    public IPage<FileNodeVO> listTeamFiles(Long spaceId, Long parentId, int page, int size) {
        if (parentId != null && parentId > 0) {
            validateAccessible(parentId);
        }
        Page<FileNode> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getSpaceId, spaceId)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .and(w -> w.eq(FileNode::getNodeType, NodeType.FOLDER.getCode())
                        .or().eq(FileNode::getUploadStatus, UploadStatus.COMPLETED.getCode()));
        if (parentId != null && parentId > 0) {
            wrapper.eq(FileNode::getParentId, parentId);
        } else {
            wrapper.and(w -> w.isNull(FileNode::getParentId).or().eq(FileNode::getParentId, 0));
        }
        wrapper.orderByAsc(FileNode::getNodeType)
               .orderByDesc(FileNode::getUpdatedAt);
        return fileNodeMapper.selectPage(pageParam, wrapper).convert(this::toVO);
    }

    @Override
    @Transactional
    public FileNodeVO createTeamFolder(Long spaceId, Long parentId, String folderName) {
        validateFileName(folderName);
        Long userId = UserContext.getUserId();
        String parentPath = validateAndGetParentPath(parentId);

        Long effectiveParentId = (parentId == null) ? 0L : parentId;
        if (fileNodeMapper.countByParentAndName(effectiveParentId, folderName) > 0) {
            throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS);
        }

        FileNode folder = new FileNode();
        folder.setParentId(effectiveParentId);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setName(folderName);
        folder.setPath(parentPath + "/" + folderName);
        folder.setStatus(NodeStatus.NORMAL.getCode());
        folder.setUploadStatus(UploadStatus.COMPLETED.getCode());
        folder.setOwnerId(userId);
        folder.setUploaderId(userId);
        folder.setSpaceId(spaceId);
        folder.setRefCount(REF_COUNT_NONE);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        reliableEventPublisher.publishFileIndex(folder, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(folder, SyncChangeEvent.ChangeType.CREATE);
        return toVO(folder);
    }

    @Override
    public void validateTeamNode(Long spaceId, Long nodeId) {
        if (nodeId == null || nodeId <= 0) return;
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getId, nodeId)
                .eq(FileNode::getSpaceId, spaceId)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
        if (fileNodeMapper.selectCount(wrapper) == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "文件不属于该团队空间");
        }
    }

    @Override
    @Transactional
    public FileNodeVO renameTeamFile(Long spaceId, Long nodeId, String newName) {
        validateFileName(newName);
        validateTeamNode(spaceId, nodeId);
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 编辑保护：团队文件正在编辑时禁止重命名（TC-18）
        assertNotEditing(node);
        String oldPath = node.getPath();
        String parentPath = oldPath.substring(0, oldPath.lastIndexOf("/"));

        LambdaQueryWrapper<FileNode> dupWrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, node.getParentId())
                .eq(FileNode::getName, newName)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .ne(FileNode::getId, nodeId);
        if (fileNodeMapper.selectCount(dupWrapper) > 0) {
            throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS);
        }

        String newPath = parentPath + "/" + newName;
        node.setName(newName);
        node.setPath(newPath);
        fileNodeMapper.updateById(node);
        if (node.isFolder()) {
            fileNodeMapper.updateChildrenPath(oldPath, newPath);
        }
        publishMetaUpdate(node, newPath);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.RENAME, oldPath);
        return toVO(node);
    }

    @Override
    @Transactional
    public void deleteTeamFiles(Long spaceId, List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            validateTeamNode(spaceId, nodeId);
            FileNode node = fileNodeMapper.selectById(nodeId);
            if (node == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
                continue;
            }
            // 编辑保护：团队文件正在编辑时禁止删除（TC-18）
            assertNotEditing(node);
            // 只置被删节点自身为回收态；子孙 status 不动，访问时由祖先链校验拦截
            node.setStatus(NodeStatus.RECYCLED.getCode());
            node.setUpdatedAt(LocalDateTime.now());
            fileNodeMapper.updateById(node);
            // 回收后该节点立即不可访问：失效可访问性缓存
            invalidateAccessible(nodeId);
            // ES/同步：仅移除被删节点自身（子孙由搜索侧祖先链过滤、同步端递归删除兜底）
            reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.DELETE);
            reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.DELETE);
            for (FileNode descendant : collectDescendants(nodeId)) {
                // 只改变被删文件夹自身状态：子孙不发事件（同步端文件夹 DELETE 递归删本地子树；
                // 搜索侧按祖先链过滤兜底）。此处仅级联失效可访问性缓存（内存操作，无 DB 写入）
                invalidateAccessible(descendant.getId());
            }
        }
    }

    @Override
    @Transactional
    public void moveTeamFiles(Long spaceId, List<Long> nodeIds, Long targetParentId) {
        // 校验目标也属于同一 spaceId
        if (targetParentId != null && targetParentId > 0) {
            validateTeamNode(spaceId, targetParentId);
        }
        String targetPath = validateAndGetParentPath(targetParentId);
        for (Long nodeId : nodeIds) {
            validateTeamNode(spaceId, nodeId);
            FileNode node = fileNodeMapper.selectById(nodeId);
            if (node == null) continue;
            // 编辑保护：团队文件正在编辑时禁止移动（TC-18）
            assertNotEditing(node);
            if (nodeId.equals(targetParentId)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "不能将文件移动到自身");
            }
            if (fileNodeMapper.countByParentAndName(targetParentId, node.getName()) > 0) {
                throw new BusinessException(ResultCode.FILE_ALREADY_EXISTS.getCode(),
                        "目标目录已存在同名: " + node.getName());
            }
            String oldPath = node.getPath();
            String newPath = targetPath + "/" + node.getName();
            node.setParentId(targetParentId);
            node.setPath(newPath);
            fileNodeMapper.updateById(node);
            // 移动后祖先链变化：失效该节点可访问性缓存（子孙由 TTL 兜底）
            invalidateAccessible(nodeId);
            if (node.isFolder()) {
                fileNodeMapper.updateChildrenPath(oldPath, newPath);
            }
            publishMetaUpdate(node, newPath);
            reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.MOVE, oldPath);
        }
    }

    @Override
    @Transactional
    public void copyTeamFiles(Long spaceId, List<Long> nodeIds, Long targetParentId) {
        if (targetParentId != null && targetParentId > 0) {
            validateTeamNode(spaceId, targetParentId);
        }
        String targetPath = validateAndGetParentPath(targetParentId);
        for (Long nodeId : nodeIds) {
            validateTeamNode(spaceId, nodeId);
            FileNode node = fileNodeMapper.selectById(nodeId);
            if (node == null) continue;
            copyTeamNodeRecursive(node, targetParentId, targetPath);
        }
    }


    @Override
    public List<FileTreeNodeVO> getTeamFolderTree(Long spaceId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getNodeType, NodeType.FOLDER.getCode())
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .eq(FileNode::getSpaceId, spaceId)
                .orderByAsc(FileNode::getName);
        List<FileNode> folders = fileNodeMapper.selectList(wrapper);
        Map<Long, List<FileNode>> parentIdMap = folders.stream()
                .collect(Collectors.groupingBy(FileNode::getParentId));
        return buildTreeNode(parentIdMap, 0L);
    }


    @Override
    public FileNodeVO resolveByPath(String path) {
        Long userId = UserContext.getUserId();
        return resolvePathInternal(path, userId, null);
    }

    @Override
    public FileNodeVO resolveTeamByPath(Long spaceId, String path) {
        return resolvePathInternal(path, null, spaceId);
    }


    @Override
    public FileNodeVO getTeamNodeById(Long spaceId, Long nodeId) {
        validateTeamNode(spaceId, nodeId);
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        validateAccessible(nodeId);
        return toVO(node);
    }

    private FileNodeVO resolvePathInternal(String path, Long ownerId, Long spaceId) {
        if (path == null) {
            path = "";
        }
        path = path.trim();
        // Normalize: strip leading/trailing slashes
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Root
        if (path.isEmpty()) {
            FileNode root = new FileNode();
            root.setId(0L);
            root.setParentId(0L);
            root.setNodeType(NodeType.FOLDER.getCode());
            root.setName("");
            root.setPath("");
            root.setStatus(NodeStatus.NORMAL.getCode());
            return toVO(root);
        }

        String[] segments = path.split("/");
        Long currentParentId = 0L;
        FileNode current = null;
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, currentParentId)
                    .eq(FileNode::getName, segment)
                    .eq(FileNode::getNodeType, NodeType.FOLDER.getCode())
                    .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
            if (ownerId != null) {
                wrapper.eq(FileNode::getOwnerId, ownerId);
            }
            if (spaceId != null) {
                wrapper.eq(FileNode::getSpaceId, spaceId);
            }
            current = fileNodeMapper.selectOne(wrapper);
            if (current == null) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(),
                        "路径不存在: " + path);
            }
            currentParentId = current.getId();
        }
        if (current == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(),
                    "路径不存在: " + path);
        }
        return toVO(current);
    }

    private void copyTeamNodeRecursive(FileNode source, Long targetParentId, String targetPath) {
        Long userId = UserContext.getUserId();
        String newName = resolveNameConflict(targetParentId, source.getName());
        String newPath = targetPath + "/" + newName;

        FileNode copy = new FileNode();
        copy.setParentId(targetParentId);
        copy.setNodeType(source.getNodeType());
        copy.setName(newName);
        copy.setPath(newPath);
        copy.setFileSize(source.getFileSize());
        copy.setFileMd5(source.getFileMd5());
        copy.setContentType(source.getContentType());
        copy.setSuffix(source.getSuffix());
        copy.setStoragePath(source.getStoragePath());
        copy.setStatus(NodeStatus.NORMAL.getCode());
        copy.setUploadStatus(UploadStatus.COMPLETED.getCode());
        copy.setOwnerId(userId);
        copy.setUploaderId(userId);
        copy.setSpaceId(source.getSpaceId());
        copy.setRefCount(REF_COUNT_INITIAL);
        copy.setVersion(0);
        copy.setThumbnailPath(source.getThumbnailPath());
        // 文件复制：归属到源对象并 +1 引用（去重）；旧数据无 object_id 时回退 md5 重算
        if (source.isFile() && source.getFileMd5() != null && source.getObjectId() != null) {
            Long tenantId = source.getTenantId() != null ? source.getTenantId() : UserContext.getTenantId();
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            FileObject object = fileObjectService.acquire(tenantId, source.getFileMd5(), size,
                    () -> source.getStoragePath());
            if (object != null) {
                copy.setObjectId(object.getId());
                copy.setStoragePath(object.getStoragePath());
            }
        }
        fileNodeMapper.insert(copy);

        if (source.isFile() && source.getFileMd5() != null) {
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            checkTeamQuota(source.getSpaceId(), size);
            cloudStorageService.checkCapacity(size);
            // 旧数据（无 object_id）回退按 md5 重算引用
            if (copy.getObjectId() == null) {
                incrementRefCount(source.getFileMd5());
            }
            // 原子扣减：并发超配额时 update 返回 0，抛异常回滚本次复制（TASK-003）
            if (size > 0 && teamStorageMapper.updateTeamStorageUsed(source.getSpaceId(), size) <= 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
        } else if (source.isFolder()) {
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
            LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, source.getId())
                    .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
            List<FileNode> children = fileNodeMapper.selectList(wrapper);
            for (FileNode child : children) {
                copyTeamNodeRecursive(child, copy.getId(), newPath);
            }
        }
    }


    @Override
    public List<Map<String, Object>> storageByType() {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> raw = fileNodeMapper.storageByType(userId, tenantId);
        // 将 suffix 映射为文件类型分类
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : raw) {
            String suffix = String.valueOf(row.get("type")).toLowerCase();
            String type = classifyFileType(suffix);
            // 合并相同类型的统计
            boolean found = false;
            for (Map<String, Object> r : result) {
                if (r.get("type").equals(type)) {
                    r.put("size", ((Number) r.get("size")).longValue() + ((Number) row.get("size")).longValue());
                    found = true;
                    break;
                }
            }
            if (!found) {
                Map<String, Object> r = new java.util.HashMap<>();
                r.put("type", type);
                r.put("size", ((Number) row.get("size")).longValue());
                result.add(r);
            }
        }
        return result;
    }

    /** 将文件后缀归类为大类：image/video/document/audio/archive/other */
    private String classifyFileType(String suffix) {
        if (suffix == null || suffix.equals("null")) return "other";
        return switch (suffix) {
            case "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico" -> "image";
            case "mp4", "avi", "mkv", "mov", "wmv", "flv", "rmvb" -> "video";
            case "doc", "docx", "pdf", "txt", "xls", "xlsx", "ppt", "pptx", "md" -> "document";
            case "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio";
            case "zip", "rar", "7z", "tar", "gz", "bz2" -> "archive";
            default -> "other";
        };
    }

    @Override
    public List<Map<String, Object>> findDuplicates() {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        return fileNodeMapper.findDuplicates(userId, tenantId);
    }

    @Override
    public List<FileNodeVO> findDuplicateDetail(String md5) {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        List<FileNode> nodes = fileNodeMapper.findByMd5(userId, tenantId, md5);
        return nodes.stream().map(this::toVO).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public int versionCount(Long nodeId) {
        return fileNodeMapper.countVersions(nodeId);
    }

    @Override
    @Transactional
    public Map<String, Object> cleanupDuplicates(String md5) {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();

        // 查询同 MD5 的所有文件节点（按 created_at ASC，最早的在前）
        List<FileNode> nodes = fileNodeMapper.findByMd5(userId, tenantId, md5);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", nodes.size());

        if (nodes.size() <= 1) {
            result.put("deletedCount", 0);
            result.put("skippedCount", 0);
            return result;
        }

        int deletedCount = 0;
        int skippedCount = 0;
        boolean kept = false;

        for (FileNode node : nodes) {
            // 有历史版本的文件跳过，不删除也不作为保留项
            if (fileNodeMapper.countVersions(node.getId()) > 0) {
                skippedCount++;
                continue;
            }
            if (!kept) {
                // 第一个无历史版本的文件保留（created_at 最早的）
                kept = true;
                result.put("keptId", node.getId());
                result.put("keptName", node.getName());
                continue;
            }
            // 其余无历史版本的文件移入回收站
            node.setStatus(NodeStatus.RECYCLED.getCode());
            node.setUpdatedAt(java.time.LocalDateTime.now());
            fileNodeMapper.updateById(node);
            // 回收后立即不可访问：失效可访问性缓存
            invalidateAccessible(node.getId());
            // 从搜索索引移除
            reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.DELETE);
            deletedCount++;
        }

        result.put("deletedCount", deletedCount);
        result.put("skippedCount", skippedCount);
        return result;
    }

    @Override
    @Transactional
    public void setHidden(Long nodeId, boolean hidden) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || !node.isNormal()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 权限校验
        Long userId = UserContext.getUserId();
        // 个人文件：仅属主可设置隐藏；团队文件由团队权限前置校验
        if ((node.getSpaceId() == null || node.getSpaceId() <= 0) && !node.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        node.setHidden(hidden ? 1 : 0);
        fileNodeMapper.updateById(node);
    }

    @Override
    public List<FileNodeVO> listHidden() {
        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getOwnerId, userId)
                .eq(FileNode::getTenantId, tenantId)
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                .eq(FileNode::getHidden, 1)
                .orderByDesc(FileNode::getUpdatedAt);
        return fileNodeMapper.selectList(wrapper).stream().map(this::toVO).collect(java.util.stream.Collectors.toList());
    }
}
