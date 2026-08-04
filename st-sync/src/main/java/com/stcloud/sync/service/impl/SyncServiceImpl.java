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
import com.stcloud.sync.entity.SyncRoot;
import com.stcloud.sync.mapper.SyncRootMapper;
import com.stcloud.sync.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final SyncRootMapper syncRootMapper;
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

        // 校验云端文件夹归属与类型
        FileNode folder = fileService.getNodeByIdAndOwner(request.getCloudFolderNodeId());
        if (!folder.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅支持同步文件夹");
        }

        // 同一文件夹不可重复注册
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

    @Override
    public Result<SyncDeltaResponse> delta(Long rootId, Long since, int page) {
        Long userId = UserContext.getUserId();
        SyncRoot root = getOwnedRoot(rootId, userId);

        // 校验根文件夹仍归属当前用户
        FileNode rootFolder = fileService.getNodeByIdAndOwner(root.getCloudFolderNodeId());
        String rootPath = rootFolder.getPath();

        // since -> LocalDateTime（空或0表示全量）
        LocalDateTime sinceTime = null;
        if (since != null && since > 0) {
            sinceTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(since), ZoneId.systemDefault());
        }

        // 查询根文件夹及其递归子节点中 updated_at > since 的记录
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getOwnerId, userId)
                .and(w -> w.eq(FileNode::getId, rootFolder.getId())
                        .or().likeRight(FileNode::getPath, rootPath + PATH_SEP));
        if (sinceTime != null) {
            wrapper.gt(FileNode::getUpdatedAt, sinceTime);
        }
        wrapper.orderByAsc(FileNode::getUpdatedAt);
        wrapper.last("LIMIT " + (PAGE_SIZE + 1) + " OFFSET " + (page * PAGE_SIZE));

        List<FileNode> nodes = fileNodeMapper.selectList(wrapper);
        boolean hasMore = nodes.size() > PAGE_SIZE;
        if (hasMore) {
            nodes = nodes.subList(0, PAGE_SIZE);
        }

        List<SyncDeltaItem> changes = new ArrayList<>();
        for (FileNode node : nodes) {
            changes.add(toDeltaItem(node, rootPath));
        }

        // 新游标 = 服务端当前时间
        long newCursor = System.currentTimeMillis();

        SyncDeltaResponse resp = new SyncDeltaResponse();
        resp.setCursor(newCursor);
        resp.setHasMore(hasMore);
        resp.setChanges(changes);
        return Result.success(resp);
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
        vo.setCursor(root.getSyncCursor());
        vo.setCreatedAt(root.getCreatedAt());
        vo.setUpdatedAt(root.getUpdatedAt());
        return vo;
    }

    private SyncDeltaItem toDeltaItem(FileNode node, String rootPath) {
        SyncDeltaItem item = new SyncDeltaItem();
        item.setNodeId(String.valueOf(node.getId()));
        item.setParentId(String.valueOf(node.getParentId()));
        // 相对路径：去掉根路径前缀，保证以 / 开头
        String relPath = node.getPath();
        if (relPath != null && relPath.startsWith(rootPath)) {
            relPath = relPath.substring(rootPath.length());
            if (!relPath.startsWith(PATH_SEP)) {
                relPath = PATH_SEP + relPath;
            }
        }
        if (relPath == null || relPath.isEmpty()) {
            relPath = PATH_SEP;
        }
        item.setPath(relPath);
        item.setName(node.getName());
        item.setNodeType(node.getNodeType());
        item.setSize(node.getFileSize());
        item.setMd5(node.getFileMd5());
        item.setSuffix(node.getSuffix());
        item.setStatus(node.getStatus());
        item.setUpdatedAt(node.getUpdatedAt());
        return item;
    }
}