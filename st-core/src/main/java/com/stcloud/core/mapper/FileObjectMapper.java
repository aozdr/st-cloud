package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileObject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文件对象 Mapper。
 * 核心 SQL 为租户维度 md5 去重与引用计数原子增减（TASK-001）。
 */
@Mapper
public interface FileObjectMapper extends BaseMapper<FileObject> {

    /**
     * 按租户+MD5 查找正常对象（秒传/去重命中判定）
     */
    // SQL 状态含义：status = 0 正常（物理对象有效）；deleted = 0 未删除
    @Select("SELECT * FROM file_object WHERE tenant_id = #{tenantId} AND md5 = #{md5} AND status = 0 AND deleted = 0 LIMIT 1")
    FileObject selectByTenantAndMd5(@Param("tenantId") Long tenantId, @Param("md5") String md5);

    /**
     * 并发安全的创建（重复键不报错，返回 0 表示已存在）
     */
    // SQL 状态含义：新对象初始 status = 0 正常，ref_count 初始 1
    @Insert("INSERT IGNORE INTO file_object (tenant_id, md5, size, storage_path, ref_count, status, created_at, updated_at, deleted) "
            + "VALUES (#{tenantId}, #{md5}, #{size}, #{storagePath}, 1, 0, NOW(), NOW(), 0)")
    int insertIgnore(FileObject object);

    /**
     * 引用计数 +1（原子）
     */
    // SQL 状态含义：仅正常(0)未删除对象可增加引用
    @Update("UPDATE file_object SET ref_count = ref_count + 1 WHERE id = #{id} AND status = 0 AND deleted = 0")
    int incrementRefCount(@Param("id") Long id);

    /**
     * 引用计数 -1（原子，不为负）
     */
    // SQL 状态含义：ref_count > 0 防止减为负；仅正常(0)未删除对象
    @Update("UPDATE file_object SET ref_count = ref_count - 1 WHERE id = #{id} AND ref_count > 0 AND status = 0 AND deleted = 0")
    int decrementRefCount(@Param("id") Long id);

    /**
     * 查询当前引用计数
     */
    // SQL 状态含义：deleted = 0 未删除
    @Select("SELECT ref_count FROM file_object WHERE id = #{id} AND deleted = 0")
    Integer getRefCount(@Param("id") Long id);

    /**
     * 标记对象失效（物理对象已删除后调用，防止被再次复用）
     */
    // SQL 状态含义：status = 1 已删除（失效），deleted = 1 逻辑删除
    @Update("UPDATE file_object SET status = 1, deleted = 1 WHERE id = #{id}")
    int markDeleted(@Param("id") Long id);

    /**
     * 恢复已软删除的同 md5 对象（去重墓碑场景）：物理对象已重新上传，
     * 重置 ref_count=0（由调用方原子 +1，避免并发覆盖），恢复 status/deleted 为正常。
     */
    // SQL 状态含义：仅删除态（deleted=1 或 status!=0）记录可被恢复
    @Update("UPDATE file_object SET size = #{size}, storage_path = #{storagePath}, ref_count = 0, status = 0, deleted = 0, updated_at = NOW() "
            + "WHERE tenant_id = #{tenantId} AND md5 = #{md5} AND (deleted = 1 OR status <> 0)")
    int revive(@Param("tenantId") Long tenantId, @Param("md5") String md5,
               @Param("storagePath") String storagePath, @Param("size") long size);
}
