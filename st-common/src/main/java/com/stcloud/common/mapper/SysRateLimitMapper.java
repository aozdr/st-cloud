package com.stcloud.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.common.entity.SysRateLimit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysRateLimitMapper extends BaseMapper<SysRateLimit> {
}