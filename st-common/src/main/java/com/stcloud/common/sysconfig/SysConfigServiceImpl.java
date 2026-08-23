package com.stcloud.common.sysconfig;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.entity.SysConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.mapper.SysConfigMapper;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 全局配置实现。读取走进程内/Redis TTL 缓存，更新后清缓存；仅允许白名单前缀写入。
 */
@Slf4j
@Service
public class SysConfigServiceImpl implements SysConfigService {

    /** 分享防爆破配置分组前缀（可后台修改的 key 白名单） */
    public static final String SHARE_SECURITY_PREFIX = "share.brute_force.";

    private static final String CACHE_PREFIX = "sys_config:";
    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final SysConfigMapper sysConfigMapper;
    private final CacheFactory cacheFactory;

    private Cache cache;

    public SysConfigServiceImpl(SysConfigMapper sysConfigMapper, CacheFactory cacheFactory) {
        this.sysConfigMapper = sysConfigMapper;
        this.cacheFactory = cacheFactory;
    }

    @PostConstruct
    void initCache() {
        this.cache = cacheFactory.create(CACHE_TTL_MILLIS);
    }

    @Override
    public String getValue(String key, String defaultValue) {
        String v = readRaw(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public Integer getInt(String key, Integer defaultValue) {
        String v = readRaw(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 非数字: {}", key, v);
            return defaultValue;
        }
    }

    @Override
    public Boolean getBool(String key, Boolean defaultValue) {
        String v = readRaw(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }

    @Override
    public List<SysConfig> listByGroup(String group) {
        return sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigGroup, group)
                .orderByAsc(SysConfig::getConfigKey));
    }

    @Override
    @Transactional
    public void update(String key, String value) {
        if (!isWritable(key)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该配置键不允许修改");
        }
        SysConfig existing = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (existing == null) {
            SysConfig cfg = new SysConfig();
            cfg.setConfigKey(key);
            cfg.setConfigValue(value);
            cfg.setConfigGroup(SHARE_SECURITY_PREFIX);
            cfg.setEnabled(1);
            sysConfigMapper.insert(cfg);
        } else {
            existing.setConfigValue(value);
            sysConfigMapper.updateById(existing);
        }
        cache.removeByPrefix(CACHE_PREFIX);
        log.info("更新全局配置: {}={}", key, value);
    }

    private String readRaw(String key) {
        String cacheKey = CACHE_PREFIX + key;
        Object cached = cache.get(cacheKey);
        if (cached instanceof String) {
            return (String) cached;
        }
        SysConfig cfg = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        String v = (cfg != null && cfg.getEnabled() != null && cfg.getEnabled() == 1) ? cfg.getConfigValue() : null;
        if (v != null) {
            cache.put(cacheKey, v);
        }
        return v;
    }

    private boolean isWritable(String key) {
        return key != null && key.startsWith(SHARE_SECURITY_PREFIX);
    }
}
