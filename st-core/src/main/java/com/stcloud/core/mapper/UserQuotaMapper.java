package com.stcloud.core.mapper;

import com.stcloud.core.dto.StorageInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserQuotaMapper {

    @Select("SELECT storage_used AS used, storage_quota AS quota FROM sys_user WHERE id = #{userId} AND deleted = 0")
    StorageInfoVO getUserQuota(@Param("userId") Long userId);

    @Update("UPDATE sys_user SET storage_used = storage_used + #{delta} " +
            "WHERE id = #{userId} AND deleted = 0 AND storage_used + #{delta} >= 0 " +
            "AND (storage_quota IS NULL OR storage_used + #{delta} <= storage_quota)")
    int updateStorageUsed(@Param("userId") Long userId, @Param("delta") Long delta);
}
