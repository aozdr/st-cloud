package com.stcloud.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 团队空间存储用量更新（st-core 无法依赖 st-team，故在此直接操作 team_space 表）
 */
@Mapper
public interface TeamStorageMapper {

    /** 回收站仅向有效空间拥有者/管理员开放，不能用节点上传者或系统管理员身份绕过成员关系。 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT DISTINCT s.id FROM team_space s JOIN team_member m ON m.space_id = s.id " +
            "AND m.tenant_id = s.tenant_id WHERE s.tenant_id = #{tenantId} AND s.deleted = 0 " +
            "AND s.status = 1 AND m.user_id = #{userId} AND m.deleted = 0 " +
            "AND (s.owner_id = #{userId} OR m.role = 0) " +
            "AND (m.expire_at IS NULL OR m.expire_at > CURRENT_TIMESTAMP) " +
            "AND (m.member_type IS NULL OR m.member_type = 0 OR EXISTS " +
            "(SELECT 1 FROM team_external_config c WHERE c.tenant_id = s.tenant_id " +
            "AND c.space_id = s.id AND c.allow_external = 1))")
    List<Long> findRecycleManagedSpaceIds(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("UPDATE team_space SET storage_used = storage_used + #{delta} " +
            "WHERE id = #{spaceId} AND deleted = 0 AND storage_used + #{delta} >= 0 " +
            "AND (storage_quota IS NULL OR storage_used + #{delta} <= storage_quota)")
    int updateTeamStorageUsed(@Param("spaceId") Long spaceId, @Param("delta") Long delta);

    @org.apache.ibatis.annotations.Select("SELECT storage_used AS used, storage_quota AS quota FROM team_space WHERE id = #{spaceId} AND deleted = 0")
    com.stcloud.core.dto.StorageInfoVO getTeamSpaceQuota(@Param("spaceId") Long spaceId);
}
