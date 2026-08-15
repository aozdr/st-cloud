package com.stcloud.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存 TtlCache 单元测试（TASK-003）：验证 put/get/过期/前缀失效/清空/计数语义，
 * 与 RedisTtlCacheTest 形成双实现对照。
 */
@DisplayName("内存 TTL 缓存测试")
class TtlCacheTest {

    @Test
    @DisplayName("put/get 基本读写")
    void putAndGet() {
        TtlCache cache = new TtlCache(60_000);
        cache.put("acc:1", Boolean.TRUE);
        assertEquals(Boolean.TRUE, cache.get("acc:1"));
        assertNull(cache.get("acc:2"));
    }

    @Test
    @DisplayName("过期后 get 返回 null")
    void expiredEntryReturnsNull() throws InterruptedException {
        TtlCache cache = new TtlCache(50);
        cache.put("acc:1", Boolean.TRUE);
        assertNotNull(cache.get("acc:1"));
        Thread.sleep(80);
        assertNull(cache.get("acc:1"));
    }

    @Test
    @DisplayName("removeByPrefix 批量失效匹配前缀的键")
    void removeByPrefix() {
        TtlCache cache = new TtlCache(60_000);
        cache.put("acc:1", Boolean.TRUE);
        cache.put("acc:2", Boolean.TRUE);
        cache.put("perm:1", Boolean.TRUE);

        cache.removeByPrefix("acc:");

        assertNull(cache.get("acc:1"));
        assertNull(cache.get("acc:2"));
        assertEquals(Boolean.TRUE, cache.get("perm:1"));
    }

    @Test
    @DisplayName("clear 清空全部")
    void clearAll() {
        TtlCache cache = new TtlCache(60_000);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    @DisplayName("size 反映当前条目数")
    void sizeReflectsEntries() {
        TtlCache cache = new TtlCache(60_000);
        assertEquals(0, cache.size());
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(2, cache.size());
    }
}