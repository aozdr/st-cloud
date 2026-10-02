package com.stcloud.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 后台扫描只读租户目录；sys_tenant 是系统表，不依赖当前租户上下文。 */
@Mapper
public interface TenantScanMapper {
    @Select("SELECT id FROM sys_tenant WHERE deleted = 0 AND status = 1 ORDER BY id")
    List<Long> selectEnabledTenantIds();
}
