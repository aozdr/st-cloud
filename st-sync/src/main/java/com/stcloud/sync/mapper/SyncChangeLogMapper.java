package com.stcloud.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.sync.entity.SyncChangeLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncChangeLogMapper extends BaseMapper<SyncChangeLog> {
}
