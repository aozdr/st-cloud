package com.stcloud.common.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量线程安全 TTL 缓存（TASK-005）。
 * <p>
 * 用于权限 / 可访问性等「可重建派生数据」的短期缓存，避免高频重复计算（如文件夹权限向上遍历、
 * 祖先链可访问性递归查询）。get 时惰性清除过期条目；removeByPrefix 支持按前缀批量失效，
 * 供权限变更、节点结构变更时精确清除相关键。
 * <p>
 * 注意：本缓存为进程内缓存，多实例部署下实例间以 TTL 兜底最终一致；变更发生时需调用失效方法。
 */
public class TtlCache implements Cache {

    /** 缓存条目：值 + 过期时间戳 */
    private static final class Entry {
        final Object value;
        final long expireAt;

        Entry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Object get(String key) {
        Entry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            map.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public void put(String key, Object value) {
        map.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public void removeByPrefix(String prefix) {
        map.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public int size() {
        return map.size();
    }
}
