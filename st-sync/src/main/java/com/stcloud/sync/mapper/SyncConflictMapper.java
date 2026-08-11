package com.stcloud.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.sync.entity.SyncConflict;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncConflictMapper extends BaseMapper<SyncConflict> {
}