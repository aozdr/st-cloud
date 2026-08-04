package com.stcloud.common.ratelimit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.entity.SysRateLimit;
import com.stcloud.common.mapper.SysRateLimitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限速规则缓存 - 按租户隔离加载,短TTL自动刷新,管理端变更后主动失效
 */
@Component
@RequiredArgsConstructor
public class SpeedLimitCache {

    private final SysRateLimitMapper mapper;

    private static final long CACHE_TTL_MS = 30_000L;

    private final Map<Long, TenantEntry> entries = new ConcurrentHashMap<>();

    /** 用户维度规则 */
    public SysRateLimit getUserRule(Long userId) {
        if (userId == null) return null;
        return getEntry().userRules.get(userId);
    }

    /** 角色维度规则(可能多条) */
    public List<SysRateLimit> getRoleRules(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return Collections.emptyList();
        Map<String, SysRateLimit> roleRules = getEntry().roleRules;
        List<SysRateLimit> matched = new ArrayList<>();
        for (String code : roleCodes) {
            SysRateLimit r = roleRules.get(code);
            if (r != null) matched.add(r);
        }
        return matched;
    }

    /** 失效全部缓存 */
    public void evict() {
        entries.clear();
    }

    private TenantEntry getEntry() {
        Long tenantId = TenantContext.getTenantId();
        long now = System.currentTimeMillis();
        TenantEntry e = entries.get(tenantId);
        if (e == null || now - e.loadedAt > CACHE_TTL_MS) {
            synchronized (this) {
                e = entries.get(tenantId);
                if (e == null || System.currentTimeMillis() - e.loadedAt > CACHE_TTL_MS) {
                    e = load();
                    entries.put(tenantId, e);
                }
            }
        }
        return e;
    }

    private TenantEntry load() {
        List<SysRateLimit> rules = mapper.selectList(
                new LambdaQueryWrapper<SysRateLimit>().eq(SysRateLimit::getEnabled, 1));
        Map<Long, SysRateLimit> userRules = new HashMap<>();
        Map<String, SysRateLimit> roleRules = new HashMap<>();
        for (SysRateLimit rule : rules) {
            if (rule.getScope() == null) continue;
            if (rule.getScope() == 0 && rule.getTargetId() != null) {
                userRules.put(rule.getTargetId(), rule);
            } else if (rule.getScope() == 1 && rule.getTargetCode() != null) {
                roleRules.put(rule.getTargetCode(), rule);
            }
        }
        return new TenantEntry(userRules, roleRules, System.currentTimeMillis());
    }

    private static class TenantEntry {
        final Map<Long, SysRateLimit> userRules;
        final Map<String, SysRateLimit> roleRules;
        final long loadedAt;

        TenantEntry(Map<Long, SysRateLimit> userRules, Map<String, SysRateLimit> roleRules, long loadedAt) {
            this.userRules = userRules;
            this.roleRules = roleRules;
            this.loadedAt = loadedAt;
        }
    }
}