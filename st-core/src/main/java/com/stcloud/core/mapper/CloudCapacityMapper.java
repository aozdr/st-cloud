package com.stcloud.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 云盘总容量 - 个人与团队共享的物理存储上限
 */
@Mapper
public interface CloudCapacityMapper {

    /**
     * 读取当前租户的云盘总容量（NULL 表示不限）
     */
    @Select("SELECT cloud_total_capacity FROM sys_tenant WHERE id = #{tenantId} AND deleted = 0")
    Long getCloudTotalCapacity(@Param("tenantId") Long tenantId);

    /**
     * 设置当前租户的云盘总容量
     */
    @Update("UPDATE sys_tenant SET cloud_total_capacity = #{capacity} WHERE id = #{tenantId} AND deleted = 0")
    int setCloudTotalCapacity(@Param("tenantId") Long tenantId, @Param("capacity") Long capacity);

    /**
     * 统计所有用户已分配配额之和（storage_quota 非空部分）
     */
    @Select("SELECT COALESCE(SUM(storage_quota), 0) FROM sys_user WHERE deleted = 0 AND tenant_id = #{tenantId}")
    Long sumUserQuota(@Param("tenantId") Long tenantId);

    /**
     * 统计所有团队空间已分配配额之和（storage_quota 非空部分）
     */
    @Select("SELECT COALESCE(SUM(storage_quota), 0) FROM team_space WHERE deleted = 0 AND tenant_id = #{tenantId}")
    Long sumTeamQuota(@Param("tenantId") Long tenantId);

    /**
     * 统计云盘已用容量 = 全部用户 storage_used + 全部团队空间 storage_used
     * 与上传/复制/删除的增量记账点保持一致
     */
    @Select("SELECT (SELECT COALESCE(SUM(storage_used), 0) FROM sys_user WHERE deleted = 0 AND tenant_id = #{tenantId}) + (SELECT COALESCE(SUM(storage_used), 0) FROM team_space WHERE deleted = 0 AND tenant_id = #{tenantId})")
    Long sumCloudStorageUsed(@Param("tenantId") Long tenantId);
}
