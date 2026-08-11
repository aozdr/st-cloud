package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileFavorite;
import com.stcloud.core.entity.FileNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;

@Mapper
public interface FileFavoriteMapper extends BaseMapper<FileFavorite> {

    /**
     * 查询当前用户收藏的文件节点列表（JOIN file_node，仅返回正常状态文件）。
     * 过滤已删除/回收站文件，避免收藏列表出现失效条目。
     * 显式带 tenant_id 条件，确保自定义 SQL 也能被租户隔离覆盖。
     */
    @Select("SELECT fn.* FROM file_favorite fav " +
            "JOIN file_node fn ON fav.file_node_id = fn.id AND fn.deleted = 0 AND fn.status = 0 " +
            "WHERE fav.user_id = #{userId} AND fav.tenant_id = #{tenantId} AND fav.deleted = 0 " +
            "ORDER BY fav.created_at DESC")
    List<FileNode> selectFavoriteNodes(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 查询当前用户收藏的文件节点ID列表（轻量，供前端判断收藏状态）。
     */
    @Select("SELECT file_node_id FROM file_favorite WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND deleted = 0")
    List<Long> selectFavoriteNodeIds(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 分页查询当前用户收藏的文件节点列表（JOIN file_node，仅返回正常状态文件）。
     * MyBatis-Plus 自动注入分页参数，无需手写 LIMIT。
     */
    @Select("SELECT fn.* FROM file_favorite fav " +
            "JOIN file_node fn ON fav.file_node_id = fn.id AND fn.deleted = 0 AND fn.status = 0 " +
            "WHERE fav.user_id = #{userId} AND fav.tenant_id = #{tenantId} AND fav.deleted = 0 " +
            "ORDER BY fav.created_at DESC")
    IPage<FileNode> selectFavoriteNodesPage(IPage<FileNode> page,
                                            @Param("userId") Long userId,
                                            @Param("tenantId") Long tenantId);
}