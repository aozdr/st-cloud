package com.stcloud.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.sync.dto.*;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.entity.SyncConflict;
import com.stcloud.sync.entity.SyncExclusion;
import com.stcloud.sync.entity.SyncRoot;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.mapper.SyncConflictMapper;
import com.stcloud.sync.mapper.SyncExclusionMapper;
import com.stcloud.sync.mapper.SyncRootMapper;
import com.stcloud.sync.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final SyncRootMapper syncRootMapper;
    private final SyncChangeLogMapper syncChangeLogMapper;
    private final SyncExclusionMapper syncExclusionMapper;
    private final SyncConflictMapper syncConflictMapper;
    private final FileNodeMapper fileNodeMapper;
    private final FileService fileService;

    private static final int PAGE_SIZE = 500;
    private static final String PATH_SEP = "/";

    @Override
    @Transactional
    public Result<SyncRootVO> createRoot(CreateSyncRootRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        FileNode folder = fileService.getNodeByIdAndOwner(request.getCloudFolderNodeId());
        if (!folder.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅支持同步文件夹");
        }

        Long exists = syncRootMapper.selectCount(
                new LambdaQueryWrapper<SyncRoot>()
                        .eq(SyncRoot::getUserId, userId)
                        .eq(SyncRoot::getCloudFolderNodeId, request.getCloudFolderNodeId()));
        if (exists > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "该文件夹已注册为同步根");
        }

        SyncRoot root = new SyncRoot();
        root.setUserId(userId);
        root.setCloudFolderNodeId(request.getCloudFolderNodeId());
        root.setLocalPathHint(request.getLocalPathHint());
        root.setStatus(0);
        root.setConflictStrategy("keep_both");
        root.setSyncCursor(0L);
        syncRootMapper.insert(root);

        return Result.success(toVO(root, folder));
    }

    @Override
    public Result<List<SyncRootVO>> listRoots() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        List<SyncRoot> roots = syncRootMapper.selectList(
                new LambdaQueryWrapper<SyncRoot>()
                        .eq(SyncRoot::getUserId, userId)
                        .orderByDesc(SyncRoot::getCreatedAt));
        List<SyncRootVO> voList = roots.stream().map(r -> {
            FileNode folder = null;
            try {
                folder = fileService.getNodeByIdAndOwner(r.getCloudFolderNodeId());
            } catch (Exception e) {
                log.warn("同步根关联文件夹不存在: rootId={}, folderId={}", r.getId(), r.getCloudFolderNodeId());
            }
            return toVO(r, folder);
        }).collect(Collectors.toList());
        return Result.success(voList);
    }

    @Override
    @Transactional
    public Result<Void> deleteRoot(Long rootId) {
        Long userId = UserContext.getUserId();
        SyncRoot root = getOwnedRoot(rootId, userId);
        syncRootMapper.deleteById(root.getId());
        // 同时清理排除路径
        syncExclusionMapper.delete(
                new LambdaQueryWrapper<SyncExclusion>().eq(SyncExclusion::getSyncRootId, rootId));
        return Result.success();
    }

    @Override
    @Transactional
    public Result<SyncRootVO> togglePause(Long rootId) {
        Long userId = UserContext.getUserId();
        SyncRoot root = getOwnedRoot(rootId, userId);
        root.setStatus(root.getStatus() == 0 ? 1 : 0);
        syncRootMapper.updateById(root);

        FileNode folder = null;
        try {
            folder = fileService.getNodeByIdAndOwner(root.getCloudFolderNodeId());
        } catch (Exception ignored) { }
        return Result.success(toVO(root, folder));
    }

    /**
     * 增量变更查询（基于变更日志游标 + 排除路径过滤）
     */
    @Override
    public Result<SyncDeltaResponse> delta(Long rootId, Long since, int page) {
        Long userId = UserContext.getUserId();
        SyncRoot root = getOwnedRoot(rootId, userId);

        FileNode rootFolder = fileService.getNodeByIdAndOwner(root.getCloudFolderNodeId());
        String rootPath = rootFolder.getPath();

        long cursor = since != null && since > 0 ? since : 0L;

        // 查询变更日志：id > since，按 id 升序
        LambdaQueryWrapper<SyncChangeLog> wrapper = new LambdaQueryWrapper<SyncChangeLog>()
                .eq(SyncChangeLog::getUserId, userId)
                .gt(SyncChangeLog::getId, cursor)
                .orderByAsc(SyncChangeLog::getId)
                .last("LIMIT " + (PAGE_SIZE + 1));

        List<SyncChangeLog> logs = syncChangeLogMapper.selectList(wrapper);
        boolean hasMore = logs.size() > PAGE_SIZE;
        if (hasMore) {
            logs = logs.subList(0, PAGE_SIZE);
        }

        long newCursor = logs.isEmpty() ? cursor : logs.get(logs.size() - 1).getId();

        // 加载排除路径列表，用于过滤变更
        List<String> exclusions = getExclusionPaths(rootId);

        List<SyncDeltaItem> changes = new ArrayList<>();
        for (SyncChangeLog logEntry : logs) {
            SyncDeltaItem item = toDeltaItem(logEntry, rootPath);
            // 过滤排除路径下的变更
            if (item.getPath() != null && !isExcluded(item.getPath(), exclusions)) {
                changes.add(item);
            }
            // MOVE/RENAME 的 oldPath 也要检查：如果旧路径被排除，跳过
            if (item.getOldPath() != null && isExcluded(item.getOldPath(), exclusions) && !changes.contains(item)) {
                changes.remove(item);
            }
        }

        SyncDeltaResponse resp = new SyncDeltaResponse();
        resp.setCursor(newCursor);
        resp.setHasMore(hasMore);
        resp.setChanges(changes);

        root.setLastSyncAt(LocalDateTime.now());
        syncRootMapper.updateById(root);

        return Result.success(resp);
    }

    // ==================== 选择性同步 ====================

    @Override
    public Result<List<SyncExclusionVO>> listExclusions(Long rootId) {
        Long userId = UserContext.getUserId();
        getOwnedRoot(rootId, userId);
        List<SyncExclusion> exclusions = syncExclusionMapper.selectList(
                new LambdaQueryWrapper<SyncExclusion>()
                        .eq(SyncExclusion::getSyncRootId, rootId)
                        .eq(SyncExclusion::getUserId, userId)
                        .orderByAsc(SyncExclusion::getRelativePath));
        List<SyncExclusionVO> voList = exclusions.stream()
                .map(this::toExclusionVO)
                .collect(Collectors.toList());
        return Result.success(voList);
    }

    @Override
    @Transactional
    public Result<SyncExclusionVO> addExclusion(Long rootId, AddExclusionRequest request) {
        Long userId = UserContext.getUserId();
        getOwnedRoot(rootId, userId);

        // 规范化路径：确保以 / 开头
        String relPath = request.getRelativePath();
        if (!relPath.startsWith(PATH_SEP)) {
            relPath = PATH_SEP + relPath;
        }

        // 查重
        Long exists = syncExclusionMapper.selectCount(
                new LambdaQueryWrapper<SyncExclusion>()
                        .eq(SyncExclusion::getSyncRootId, rootId)
                        .eq(SyncExclusion::getRelativePath, relPath));
        if (exists > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "该排除路径已存在");
        }

        SyncExclusion exclusion = new SyncExclusion();
        exclusion.setSyncRootId(rootId);
        exclusion.setUserId(userId);
        exclusion.setRelativePath(relPath);
        syncExclusionMapper.insert(exclusion);

        return Result.success(toExclusionVO(exclusion));
    }

    @Override
    @Transactional
    public Result<Void> removeExclusion(Long rootId, Long exclusionId) {
        Long userId = UserContext.getUserId();
        getOwnedRoot(rootId, userId);
        syncExclusionMapper.delete(
                new LambdaQueryWrapper<SyncExclusion>()
                        .eq(SyncExclusion::getId, exclusionId)
                        .eq(SyncExclusion::getSyncRootId, rootId)
                        .eq(SyncExclusion::getUserId, userId));
        return Result.success();
    }

    // ==================== 冲突策略 ====================

    @Override
    @Transactional
    public Result<SyncRootVO> updateConflictStrategy(Long rootId, UpdateConflictStrategyRequest request) {
        Long userId = UserContext.getUserId();
        SyncRoot root = getOwnedRoot(rootId, userId);
        root.setConflictStrategy(request.getConflictStrategy());
        syncRootMapper.updateById(root);

        FileNode folder = null;
        try {
            folder = fileService.getNodeByIdAndOwner(root.getCloudFolderNodeId());
        } catch (Exception ignored) { }
        return Result.success(toVO(root, folder));
    }

    // ==================== 辅助方法 ====================

    private SyncRoot getOwnedRoot(Long rootId, Long userId) {
        SyncRoot root = syncRootMapper.selectById(rootId);
        if (root == null || !userId.equals(root.getUserId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "同步根不存在或无权限");
        }
        return root;
    }

    private SyncRootVO toVO(SyncRoot root, FileNode folder) {
        SyncRootVO vo = new SyncRootVO();
        vo.setId(String.valueOf(root.getId()));
        vo.setCloudFolderNodeId(String.valueOf(root.getCloudFolderNodeId()));
        vo.setCloudFolderName(folder != null ? folder.getName() : null);
        vo.setLocalPathHint(root.getLocalPathHint());
        vo.setStatus(root.getStatus());
        vo.setConflictStrategy(root.getConflictStrategy());
        vo.setCursor(root.getSyncCursor());
        vo.setLastSyncAt(root.getLastSyncAt());
        vo.setCreatedAt(root.getCreatedAt());
        vo.setUpdatedAt(root.getUpdatedAt());
        return vo;
    }

    private SyncExclusionVO toExclusionVO(SyncExclusion exclusion) {
        SyncExclusionVO vo = new SyncExclusionVO();
        vo.setId(String.valueOf(exclusion.getId()));
        vo.setSyncRootId(String.valueOf(exclusion.getSyncRootId()));
        vo.setRelativePath(exclusion.getRelativePath());
        vo.setCreatedAt(exclusion.getCreatedAt());
        return vo;
    }

    private SyncDeltaItem toDeltaItem(SyncChangeLog logEntry, String rootPath) {
        SyncDeltaItem item = new SyncDeltaItem();
        item.setLogId(String.valueOf(logEntry.getId()));
        item.setNodeId(String.valueOf(logEntry.getFileNodeId()));
        item.setChangeType(logEntry.getChangeType());
        item.setPath(toRelativePath(logEntry.getPath(), rootPath));
        item.setOldPath(toRelativePath(logEntry.getOldPath(), rootPath));
        item.setName(logEntry.getName());
        item.setNodeType(logEntry.getNodeType());
        item.setSize(logEntry.getFileSize());
        item.setMd5(logEntry.getFileMd5());

        String name = logEntry.getName();
        if (name != null && name.contains(".")) {
            item.setSuffix(name.substring(name.lastIndexOf(".") + 1).toLowerCase());
        }

        item.setStatus("DELETE".equals(logEntry.getChangeType()) ? 1 : 0);
        item.setUpdatedAt(logEntry.getCreatedAt());
        return item;
    }

    private String toRelativePath(String absPath, String rootPath) {
        if (absPath == null || absPath.isEmpty()) {
            return null;
        }
        String relPath = absPath;
        if (relPath.startsWith(rootPath)) {
            relPath = relPath.substring(rootPath.length());
            if (!relPath.startsWith(PATH_SEP)) {
                relPath = PATH_SEP + relPath;
            }
        }
        if (relPath.isEmpty()) {
            relPath = PATH_SEP;
        }
        return relPath;
    }

    /** 获取同步根的排除路径列表 */
    private List<String> getExclusionPaths(Long rootId) {
        List<SyncExclusion> exclusions = syncExclusionMapper.selectList(
                new LambdaQueryWrapper<SyncExclusion>().eq(SyncExclusion::getSyncRootId, rootId));
        return exclusions.stream()
                .map(SyncExclusion::getRelativePath)
                .collect(Collectors.toList());
    }

    /** 判断相对路径是否被排除（路径本身或其祖先被排除） */
    private boolean isExcluded(String relPath, List<String> exclusions) {
        if (exclusions.isEmpty()) {
            return false;
        }
        // 根路径 "/" 不排除
        if (PATH_SEP.equals(relPath)) {
            return false;
        }
        for (String excl : exclusions) {
            // 精确匹配 或 是排除路径的子路径
            if (relPath.equals(excl) || relPath.startsWith(excl + PATH_SEP)) {
                return true;
            }
        }
        return false;
    }
}