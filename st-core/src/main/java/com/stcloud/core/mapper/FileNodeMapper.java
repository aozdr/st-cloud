package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
