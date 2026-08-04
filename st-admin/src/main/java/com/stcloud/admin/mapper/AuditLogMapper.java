package com.stcloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.admin.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
