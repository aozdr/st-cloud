package com.stcloud.common.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存后端工厂（TASK-003）：按配置返回缓存实现。
 * <p>
 * stcloud.cache.redis.enabled=true 且存在 RedisTemplate 时返回 Redis 缓存（多实例一致）；
 * 否则返回进程内 {@link TtlCache}（默认，单实例 / 无 Redis 环境行为不变）。
 * 注意：TTL 由调用方在 create 时传入，保持各服务原有差异化 TTL（权限 60s / 可访问性 30s）。
 */
@Component
public class CacheFactory {

    private final boolean redisEnabled;
    private final RedisTemplate<String, Object> redisTemplate;

    public CacheFactory(@Value("${stcloud.cache.redis.enabled:false}") boolean redisEnabled,
                        ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider) {
        this.redisEnabled = redisEnabled;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /** 创建缓存实例：Redis 启用且可用 → RedisTtlCache；否则内存 TtlCache */
    public Cache create(long ttlMillis) {
        if (redisEnabled && redisTemplate != null) {
            return new RedisTtlCache(redisTemplate, ttlMillis);
        }
        return new TtlCache(ttlMillis);
    }
}
