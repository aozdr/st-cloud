package com.stcloud.common.sysconfig;

import com.stcloud.common.entity.SysConfig;

import java.util.List;

/**
 * 全局配置服务：读取（带默认值 + 缓存）与更新（仅白名单前缀）可后台修改配置。
 */
public interface SysConfigService {

    String getValue(String key, String defaultValue);

    Integer getInt(String key, Integer defaultValue);

    Boolean getBool(String key, Boolean defaultValue);

    List<SysConfig> listByGroup(String group);

    void update(String key, String value);
}
