package com.stcloud.common.ratelimit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.entity.SysRateLimit;
import com.stcloud.common.mapper.SysRateLimitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限速规则缓存 - 按租户隔离加载，短 TTL 自动刷新，管理端变更后广播失效。
 * <p>
 * version 来自 Redis 自增计数：共享桶用它判断调用方配置是否过期，
 * 旧节点不会把新限速覆盖回去。Redis 不可用时 version=0，退化为本地 TTL 刷新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeedLimitCache {

    public static final String VERSION_KEY = "stcloud:rate-limit:ver";

    private final SysRateLimitMapper mapper;

    @Autowired(required = false)
    private StringRedisTemplate redis;

    private static final long CACHE_TTL_MS = 30_000L;

    private final Map<Long, TenantEntry> entries = new ConcurrentHashMap<>();

    public SysRateLimit getUserRule(Long userId) {
        if (userId == null) {
            return null;
        }
        return getEntry().userRules.get(userId);
    }

    public List<SysRateLimit> getRoleRules(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, SysRateLimit> roleRules = getEntry().roleRules;
        List<SysRateLimit> matched = new ArrayList<>();
        for (String code : roleCodes) {
            SysRateLimit rule = roleRules.get(code);
            if (rule != null) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 当前配置版本；用于共享桶的 ver 校验。 */
    public long getVersion() {
        return getEntry().version;
    }

    /** 失效全部缓存 */
    public void evict() {
        entries.clear();
    }

    private TenantEntry getEntry() {
        Long tenantId = TenantContext.getTenantId();
        long now = System.currentTimeMillis();
        TenantEntry entry = entries.get(tenantId);
        if (entry == null || now - entry.loadedAt > CACHE_TTL_MS) {
            synchronized (this) {
                entry = entries.get(tenantId);
                if (entry == null || System.currentTimeMillis() - entry.loadedAt > CACHE_TTL_MS) {
                    entry = load();
                    entries.put(tenantId, entry);
                }
            }
        }
        return entry;
    }

    private TenantEntry load() {
        // 版本号在管理端事务提交后才递增，读库前后各读一次可避免缓存「新版本 + 旧规则」
        long versionBefore = readVersion();
        List<SysRateLimit> rules = loadRules();
        long versionAfter = readVersion();
        if (versionBefore != versionAfter) {
            rules = loadRules();
            versionAfter = readVersion();
        }
        Map<Long, SysRateLimit> userRules = new HashMap<>();
        Map<String, SysRateLimit> roleRules = new HashMap<>();
        for (SysRateLimit rule : rules) {
            if (rule.getScope() == null) {
                continue;
            }
            if (rule.getScope() == 0 && rule.getTargetId() != null) {
                userRules.put(rule.getTargetId(), rule);
            } else if (rule.getScope() == 1 && rule.getTargetCode() != null) {
                roleRules.put(rule.getTargetCode(), rule);
            }
        }
        return new TenantEntry(userRules, roleRules, System.currentTimeMillis(), versionAfter);
    }

    private List<SysRateLimit> loadRules() {
        return mapper.selectList(new LambdaQueryWrapper<SysRateLimit>().eq(SysRateLimit::getEnabled, 1));
    }

    private long readVersion() {
        if (redis == null) {
            return 0L;
        }
        try {
            String value = redis.opsForValue().get(VERSION_KEY);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (RuntimeException e) {
            log.warn("读取限速配置版本失败，降级为 0：{}", e.getMessage());
            return 0L;
        }
    }

    private static final class TenantEntry {
        final Map<Long, SysRateLimit> userRules;
        final Map<String, SysRateLimit> roleRules;
        final long loadedAt;
        final long version;

        TenantEntry(Map<Long, SysRateLimit> userRules, Map<String, SysRateLimit> roleRules,
                    long loadedAt, long version) {
            this.userRules = userRules;
            this.roleRules = roleRules;
            this.loadedAt = loadedAt;
            this.version = version;
        }
    }
}
