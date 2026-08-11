package com.stcloud.core.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.core.dto.FileNodeVO;

import java.util.List;

/**
 * 文件收藏服务
 */
public interface FavoriteService {

    /**
     * 切换收藏状态：已收藏则取消，未收藏则添加。
     *
     * @param nodeId 文件节点ID
     * @return true=已收藏，false=已取消收藏
     */
    boolean toggleFavorite(Long nodeId);

    /**
     * 查询当前用户收藏的文件列表（已过滤回收站/已删除文件）。
     */
    List<FileNodeVO> listFavorites();

    /**
     * 查询当前用户收藏的文件节点ID列表（轻量，供前端判断收藏状态）。
     */
    List<Long> listFavoriteIds();

    /**
     * 分页查询当前用户收藏的文件列表（已过滤回收站/已删除文件）。
     */
    IPage<FileNodeVO> pageFavorites(int page, int size);
}