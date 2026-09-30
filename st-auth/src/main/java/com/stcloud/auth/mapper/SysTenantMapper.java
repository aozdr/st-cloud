package com.stcloud.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.auth.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
    @Select("SELECT id FROM sys_tenant WHERE id = #{tenantId} AND deleted = 0 FOR UPDATE")
    Long lockSecurityWrites(@Param("tenantId") Long tenantId);
}
