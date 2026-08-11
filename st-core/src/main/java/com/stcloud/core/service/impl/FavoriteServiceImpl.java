package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.entity.FileFavorite;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileFavoriteMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FavoriteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FavoriteServiceImpl implements FavoriteService {

    @Resource
    private FileFavoriteMapper fileFavoriteMapper;
    @Resource
    private FileNodeMapper fileNodeMapper;

    @Override
    @Transactional
    public boolean toggleFavorite(Long nodeId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // 校验文件节点存在且处于正常状态（回收站/已删除的文件不可收藏）。
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || !node.isNormal()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 权限校验：确保当前用户对该文件节点有访问权限（与 FileService.validateAccessible 逻辑一致）
        if (fileNodeMapper.countInaccessibleAncestors(nodeId) > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        // 查询是否已收藏
        FileFavorite existing = fileFavoriteMapper.selectOne(
                new LambdaQueryWrapper<FileFavorite>()
                        .eq(FileFavorite::getUserId, userId)
                        .eq(FileFavorite::getFileNodeId, nodeId));

        if (existing != null) {
            // 已收藏 -> 取消收藏
            fileFavoriteMapper.deleteById(existing.getId());
            return false;
        }

        // 未收藏 -> 添加收藏
        FileFavorite fav = new FileFavorite();
        fav.setUserId(userId);
        fav.setFileNodeId(nodeId);
        fileFavoriteMapper.insert(fav);
        return true;
    }

    @Override
    public List<FileNodeVO> listFavorites() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long tenantId = TenantContext.getTenantId();

        List<FileNode> nodes = fileFavoriteMapper.selectFavoriteNodes(userId, tenantId);
        return nodes.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public List<Long> listFavoriteIds() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long tenantId = TenantContext.getTenantId();
        return fileFavoriteMapper.selectFavoriteNodeIds(userId, tenantId);
    }

    @Override
    public IPage<FileNodeVO> pageFavorites(int page, int size) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long tenantId = TenantContext.getTenantId();

        // 分页查询收藏的文件节点（JOIN file_node，自动过滤已删除/回收站文件）
        Page<FileNode> pageParam = new Page<>(page, size);
        IPage<FileNode> result = fileFavoriteMapper.selectFavoriteNodesPage(pageParam, userId, tenantId);
        return result.convert(this::toVO);
    }

    /**
     * 将 FileNode 实体转为 VO（与 FileServiceImpl.toVO 保持一致）
     */
    private FileNodeVO toVO(FileNode node) {
        FileNodeVO vo = new FileNodeVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setNodeType(node.getNodeType());
        vo.setName(node.getName());
        vo.setPath(node.getPath());
        vo.setFileSize(node.getFileSize());
        vo.setSuffix(node.getSuffix());
        vo.setContentType(node.getContentType());
        vo.setStatus(node.getStatus());
        vo.setThumbnailPath(node.getThumbnailPath());
        vo.setCreatedAt(node.getCreatedAt());
        vo.setUpdatedAt(node.getUpdatedAt());
        return vo;
    }
}