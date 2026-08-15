package com.stcloud.common.cache;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存实现（TASK-003）：基于 RedisTemplate（JSON 序列化）实现与内存 {@link TtlCache}
 * 一致的 key / TTL / 前缀失效语义，供多实例部署消除跨实例 TTL 兜底窗口。
 * <p>
 * 所有 key 加统一命名空间前缀 stcloud:cache:，避免与其它 Redis 数据冲突；
 * 前缀失效 / 清空使用 SCAN 而非 KEYS，避免大 key 空间阻塞。
 */
public class RedisTtlCache implements Cache {

    private static final String KEY_PREFIX = "stcloud:cache:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final long ttlMillis;

    public RedisTtlCache(RedisTemplate<String, Object> redisTemplate, long ttlMillis) {
        this.redisTemplate = redisTemplate;
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + key);
    }

    @Override
    public void put(String key, Object value) {
        redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttlMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void removeByPrefix(String prefix) {
        Set<String> keys = scanKeys(KEY_PREFIX + prefix + "*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Override
    public void clear() {
        Set<String> keys = scanKeys(KEY_PREFIX + "*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Override
    public int size() {
        return scanKeys(KEY_PREFIX + "*").size();
    }

    /** 按 pattern 用 SCAN 收集全部匹配 key */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor =
                redisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }
}
