package com.stcloud.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 统计查询Mapper - 使用原生SQL避免跨模块依赖
 */
@Mapper
public interface StatsMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0")
    Long countUsers();

    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0 AND last_login_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)")
    Long countActiveUsers();

    @Select("SELECT COUNT(*) FROM file_node WHERE deleted = 0 AND status = 0")
    Long countFiles();

    @Select("SELECT COALESCE(SUM(file_size), 0) FROM file_node WHERE deleted = 0 AND status = 0 AND node_type = 1")
    Long sumStorageUsed();

    @Select("SELECT COUNT(*) FROM file_share WHERE deleted = 0")
    Long countShares();

    @Select("SELECT COUNT(*) FROM team_space WHERE deleted = 0")
    Long countTeams();

    /** 云盘总容量（取默认租户，NULL=不限） */
    @Select("SELECT cloud_total_capacity FROM sys_tenant WHERE id = 1 AND deleted = 0")
    Long getCloudTotalCapacity();

    /** 云盘已用容量 = 全部用户 storage_used + 全部团队空间 storage_used */
    @Select("SELECT (SELECT COALESCE(SUM(storage_used), 0) FROM sys_user WHERE deleted = 0) + (SELECT COALESCE(SUM(storage_used), 0) FROM team_space WHERE deleted = 0)")
    Long sumCloudStorageUsed();

    /** 设置云盘总容量 */
    @org.apache.ibatis.annotations.Update("UPDATE sys_tenant SET cloud_total_capacity = #{capacity} WHERE id = 1 AND deleted = 0")
    int setCloudTotalCapacity(@org.apache.ibatis.annotations.Param("capacity") Long capacity);
}
