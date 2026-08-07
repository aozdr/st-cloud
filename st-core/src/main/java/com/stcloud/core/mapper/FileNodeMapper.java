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
    @Select("SELECT * FROM file_node WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND status = 0 AND deleted = 0 LIMIT 1")
    FileNode selectByMd5(@Param("md5") String md5);

    /**
     * 统计同级目录下同名节点数量（重名校验）
     */
    @Select("SELECT COUNT(*) FROM file_node WHERE parent_id = #{parentId} AND name = #{name} AND status = 0 AND deleted = 0")
    int countByParentAndName(@Param("parentId") Long parentId, @Param("name") String name);

    /**
     * 批量更新子节点路径（移动/重命名时）
     * @param oldPath 旧路径前缀
     * @param newPath 新路径前缀
     */
    @Update("UPDATE file_node SET path = CONCAT(#{newPath}, SUBSTRING(path, CHAR_LENGTH(#{oldPath}) + 1)) WHERE path LIKE CONCAT(#{oldPath}, '/%') AND deleted = 0")
    int updateChildrenPath(@Param("oldPath") String oldPath, @Param("newPath") String newPath);

    /**
     * 统计共享同一 S3 物理对象（storage_path）的其他已完成文件引用数（排除指定节点）。
     * 永久删除时据此判断是否可安全删除底层存储对象。
     */
    @Select("SELECT COUNT(*) FROM file_node WHERE storage_path = #{storagePath} AND id <> #{nodeId} AND node_type = 1 AND upload_status = 2 AND deleted = 0")
    long countOtherRefsByStoragePath(@Param("storagePath") String storagePath, @Param("nodeId") Long nodeId);

    /**
     * 按 MD5 重算引用计数：将该 MD5 下所有已完成文件节点的 ref_count 置为当前节点总数。
     * 在新增（秒传/复制）或删除引用后调用，使 ref_count 与实际引用数保持一致。
     */
    @Update("UPDATE file_node SET ref_count = (SELECT cnt FROM (SELECT COUNT(*) AS cnt FROM file_node WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND deleted = 0) t) WHERE file_md5 = #{md5} AND node_type = 1 AND upload_status = 2 AND deleted = 0")
    int syncRefCountByMd5(@Param("md5") String md5);

    /**
     * 递归统计 nodeId 及其祖先链上处于非正常状态（回收/已删除）的节点数。返回 >0 表示不可访问。
     */
    @Select("WITH RECURSIVE chain AS (" +
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
}
