package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface FileNodeMapper extends BaseMapper<FileNode> {

    /**
     * 根据MD5查找已完成的文件（秒传检查）
     */
    // SQL 状态含义：node_type = 1 文件；upload_status = 2 已完成；status = 0 正常；deleted = 0 未删除
    @Select("SELECT * FROM file_node WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND status = 0 AND deleted = 0 LIMIT 1")
    FileNode selectByMd5(@Param("md5") String md5);

    /**
     * 统计同级目录下同名节点数量（重名校验）
     */
    // SQL 状态含义：status = 0 正常（排除回收站/已删除）；deleted = 0 未删除
    @Select("SELECT COUNT(*) FROM file_node WHERE parent_id = #{parentId} AND name = #{name} AND status = 0 AND deleted = 0")
    int countByParentAndName(@Param("parentId") Long parentId, @Param("name") String name);

    /**
     * 批量更新子节点路径（移动/重命名时）
     * @param oldPath 旧路径前缀
     * @param newPath 新路径前缀
     */
    // SQL 状态含义：deleted = 0 未删除（移动/重命名仅作用于未删除节点）
    @Update("UPDATE file_node SET path = CONCAT(#{newPath}, SUBSTRING(path, CHAR_LENGTH(#{oldPath}) + 1)) WHERE path LIKE CONCAT(#{oldPath}, '/%') AND deleted = 0")
    int updateChildrenPath(@Param("oldPath") String oldPath, @Param("newPath") String newPath);

    /**
     * 统计共享同一 S3 物理对象（storage_path）的其他已完成文件引用数（排除指定节点）。
     * 永久删除时据此判断是否可安全删除底层存储对象。
     */
    // SQL 状态含义：node_type = 1 文件；upload_status = 2 已完成；deleted = 0 未删除
    @Select("SELECT COUNT(*) FROM file_node WHERE storage_path = #{storagePath} AND id <> #{nodeId} AND node_type = 1 AND upload_status = 2 AND deleted = 0")
    long countOtherRefsByStoragePath(@Param("storagePath") String storagePath, @Param("nodeId") Long nodeId);

    /**
     * 按 MD5 重算引用计数：将该 MD5 下所有已完成文件节点的 ref_count 置为当前节点总数。
     * 在新增（秒传/复制）或删除引用后调用，使 ref_count 与实际引用数保持一致。
     */
    // SQL 状态含义：仅统计已完成(2)且未删除的正常文件，保证 ref_count 与实际引用数一致
    @Update("UPDATE file_node SET ref_count = (SELECT cnt FROM (SELECT COUNT(*) AS cnt FROM file_node WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND deleted = 0) t) WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND deleted = 0")
    int syncRefCountByMd5(@Param("md5") String md5);

    /**
     * 递归统计 nodeId 及其祖先链上处于非正常状态（回收/已删除）的节点数。返回 >0 表示不可访问。
     */
    // SQL 状态含义：status <> 0 表示回收站(1)/已删除(2)等非正常态，存在即判定祖先链不可访问
    @Select("WITH RECURSIVE chain(id, parent_id, status, tenant_id) AS (" +
            "SELECT id, parent_id, status, tenant_id FROM file_node WHERE id = #{nodeId} AND deleted = 0 " +
            "UNION ALL " +
            "SELECT p.id, p.parent_id, p.status, p.tenant_id FROM file_node p JOIN chain c ON p.id = c.parent_id AND p.deleted = 0" +
            ") SELECT COUNT(*) FROM chain WHERE status <> 0")
    long countInaccessibleAncestors(@Param("nodeId") Long nodeId);

    /**
     * 批量判断给定节点中"祖先链含非正常态"的节点 id（不含节点自身）。
     * 用于增量同步等场景排除回收子树中仍为 NORMAL 的节点，避免向客户端泄漏已删除内容。
     * 仅检查祖先链，故 RECYCLED 根节点本身不会被排除（需作为删除通知下发）。
     */
    // SQL 状态含义：a.status != 0 非正常态祖先（回收站/已删除），命中则排除该节点
    @Select("<script>" +
            "WITH RECURSIVE chain AS (" +
            "SELECT id AS start_id, parent_id, tenant_id FROM file_node WHERE id IN " +
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> " +
            "AND deleted = 0 " +
            "UNION ALL " +
            "SELECT c.start_id, p.parent_id, p.tenant_id FROM chain c " +
            "JOIN file_node p ON p.id = c.parent_id AND p.deleted = 0" +
            ") SELECT DISTINCT c.start_id FROM chain c " +
            "JOIN file_node a ON a.id = c.parent_id AND a.deleted = 0 AND a.status != 0" +
            "</script>")
    List<Long> findIdsWithInaccessibleAncestor(@Param("ids") Collection<Long> ids);

    /**
     * 按文件后缀分组统计存储占用（仅当前用户的正常状态文件）。
     */
    // SQL 状态含义：status = 0 正常；node_type = 1 文件；upload_status = 2 已完成
    @org.apache.ibatis.annotations.Select("SELECT IFNULL(suffix, 'other') AS type, COALESCE(SUM(file_size), 0) AS size " +
            "FROM file_node WHERE owner_id = #{userId} AND tenant_id = #{tenantId} " +
            "AND deleted = 0 AND status = 0 AND node_type = 1 AND upload_status = 2 " +
            "GROUP BY IFNULL(suffix, 'other')")
    @org.apache.ibatis.annotations.MapKey("type")
    List<java.util.Map<String, Object>> storageByType(@org.apache.ibatis.annotations.Param("userId") Long userId,
                                                      @org.apache.ibatis.annotations.Param("tenantId") Long tenantId);

    /**
     * 查询当前用户空间内的重复文件（按 MD5 分组，count > 1）。
     * 返回每组重复文件的 MD5、文件数量、总占用大小。
     */
    // SQL 状态含义：status = 0 正常；node_type = 1 文件；upload_status = 2 已完成；仅统计 file_md5 非空
    @org.apache.ibatis.annotations.Select("SELECT file_md5 AS fileMd5, COUNT(*) AS cnt, SUM(file_size) AS totalSize, " +
            "MIN(name) AS sampleName, MIN(id) AS sampleId " +
            "FROM file_node WHERE owner_id = #{userId} AND tenant_id = #{tenantId} " +
            "AND deleted = 0 AND status = 0 AND node_type = 1 AND upload_status = 2 " +
            "AND file_md5 IS NOT NULL " +
            "GROUP BY file_md5 HAVING COUNT(*) > 1 " +
            "ORDER BY totalSize DESC")
    List<java.util.Map<String, Object>> findDuplicates(@org.apache.ibatis.annotations.Param("userId") Long userId,
                                                        @org.apache.ibatis.annotations.Param("tenantId") Long tenantId);

    /**
     * 查询指定 MD5 的所有文件节点（用于重复文件详情展示）。
     */
    // SQL 状态含义：status = 0 正常；node_type = 1 文件；deleted = 0 未删除
    @org.apache.ibatis.annotations.Select("SELECT * FROM file_node WHERE owner_id = #{userId} AND tenant_id = #{tenantId} " +
            "AND deleted = 0 AND status = 0 AND node_type = 1 AND file_md5 = #{md5} ORDER BY created_at ASC")
    List<FileNode> findByMd5(@org.apache.ibatis.annotations.Param("userId") Long userId,
                             @org.apache.ibatis.annotations.Param("tenantId") Long tenantId,
                             @org.apache.ibatis.annotations.Param("md5") String md5);

    /**
     * 检查文件节点是否有历史版本记录
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM file_version WHERE file_node_id = #{nodeId}")
    int countVersions(@org.apache.ibatis.annotations.Param("nodeId") Long nodeId);
    /**
     * 原子认领合并（TASK-002 幂等守卫）：仅当节点处于上传中(1)或失败(3)（合并失败可重试）时置为合并中(4)。
     * 返回影响行数：1=认领成功；0=已被其他请求认领或当前状态不可流转（调用方需重读判断）。
     */
    @Update("UPDATE file_node SET upload_status = 4 WHERE id = #{id} AND upload_status IN (1, 3) AND deleted = 0")
    int claimMerging(@Param("id") Long id);
}
