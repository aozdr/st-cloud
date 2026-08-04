package com.stcloud.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 团队空间存储用量更新（st-core 无法依赖 st-team，故在此直接操作 team_space 表）
 */
@Mapper
public interface TeamStorageMapper {

    @Update("UPDATE team_space SET storage_used = storage_used + #{delta} WHERE id = #{spaceId} AND storage_used + #{delta} >= 0")
    int updateTeamStorageUsed(@Param("spaceId") Long spaceId, @Param("delta") Long delta);

    @org.apache.ibatis.annotations.Select("SELECT storage_used AS used, storage_quota AS quota FROM team_space WHERE id = #{spaceId} AND deleted = 0")
    com.stcloud.core.dto.StorageInfoVO getTeamSpaceQuota(@Param("spaceId") Long spaceId);
}
