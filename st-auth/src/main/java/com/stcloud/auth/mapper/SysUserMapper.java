package com.stcloud.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("SELECT id, tenant_id, status, security_version FROM sys_user "
            + "WHERE id = #{userId} AND tenant_id = #{tenantId} AND deleted = 0")
    @Results({@Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "status", property = "status"),
            @Result(column = "security_version", property = "securityVersion")})
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    SysUser selectSecurityState(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("SELECT id, tenant_id, status, storage_quota FROM sys_user "
            + "WHERE id = #{userId} AND tenant_id = #{tenantId} AND deleted = 0 FOR UPDATE")
    @Results({@Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "status", property = "status"),
            @Result(column = "storage_quota", property = "storageQuota")})
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    SysUser selectForSecurityUpdate(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("UPDATE sys_user SET security_version = security_version + 1 WHERE id = #{userId} AND tenant_id = #{tenantId} AND deleted = 0")
    int incrementSecurityVersion(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Update("UPDATE sys_user SET security_version = security_version + 1 WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "AND id IN (SELECT user_id FROM sys_user_role WHERE tenant_id = #{tenantId} AND role_id = #{roleId} AND deleted = 0)")
    int incrementRoleMembersSecurityVersion(@Param("tenantId") Long tenantId, @Param("roleId") Long roleId);
}
