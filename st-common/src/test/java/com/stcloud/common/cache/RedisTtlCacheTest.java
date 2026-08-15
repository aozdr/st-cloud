package com.stcloud.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RedisTtlCache 单元测试（TASK-003）：Mock RedisTemplate，验证命名空间前缀、TTL 写入、
 * 前缀失效（SCAN+DELETE）、清空/计数语义与内存 TtlCache 一致。纯 Mockito，不依赖真实 Redis。
 */
@DisplayName("Redis TTL 缓存测试")
class RedisTtlCacheTest {

    @SuppressWarnings("unchecked")
    private ValueOperations<String, Object> stubOps(RedisTemplate<String, Object> template) {
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        return ops;
    }

    @SuppressWarnings("unchecked")
    private Cursor<String> stubCursor(RedisTemplate<String, Object> template, String... keys) {
        Cursor<String> cursor = mock(Cursor.class);
        Iterator<String> it = Arrays.asList(keys).iterator();
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        if (keys.length > 0) {
            when(cursor.next()).thenAnswer(inv -> it.next());
        }
        when(template.scan(any(ScanOptions.class))).thenReturn(cursor);
        return cursor;
    }

    @Test
    @DisplayName("put 写入带命名空间前缀与 TTL，get 读取命中前缀")
    void putAndGetUseNamespaceAndTtl() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        ValueOperations<String, Object> ops = stubOps(template);
        when(ops.get("stcloud:cache:acc:10")).thenReturn(Boolean.TRUE);

        RedisTtlCache cache = new RedisTtlCache(template, 30_000);
        cache.put("acc:10", Boolean.TRUE);
        cache.put("acc:20", Integer.valueOf(1));

        verify(ops).set(eq("stcloud:cache:acc:10"), eq(Boolean.TRUE), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        verify(ops).set(eq("stcloud:cache:acc:20"), eq(Integer.valueOf(1)), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        assertEquals(Boolean.TRUE, cache.get("acc:10"));
        assertNull(cache.get("acc:99"));
    }

    @Test
    @DisplayName("removeByPrefix 用 SCAN 匹配并删除对应 key")
    void removeByPrefixScansAndDeletes() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        stubCursor(template, "stcloud:cache:1:10", "stcloud:cache:1:11");

        RedisTtlCache cache = new RedisTtlCache(template, 60_000);
        cache.removeByPrefix("1:");

        Set<String> expected = new HashSet<>(Arrays.asList("stcloud:cache:1:10", "stcloud:cache:1:11"));
        verify(template).delete(eq(expected));
    }

    @Test
    @DisplayName("removeByPrefix 无匹配时不删除")
    void removeByPrefixNoMatchDoesNotDelete() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        stubCursor(template);

        RedisTtlCache cache = new RedisTtlCache(template, 60_000);
        cache.removeByPrefix("nope:");

        verify(template, never()).delete(anySet());
    }

    @Test
    @DisplayName("clear 删除全部命名空间 key")
    void clearDeletesAll() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        stubCursor(template, "stcloud:cache:a", "stcloud:cache:b");

        RedisTtlCache cache = new RedisTtlCache(template, 60_000);
        cache.clear();

        Set<String> expected = new HashSet<>(Arrays.asList("stcloud:cache:a", "stcloud:cache:b"));
        verify(template).delete(eq(expected));
    }

    @Test
    @DisplayName("size 返回匹配 key 数量")
    void sizeCountsKeys() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        stubCursor(template, "stcloud:cache:a", "stcloud:cache:b", "stcloud:cache:c");

        RedisTtlCache cache = new RedisTtlCache(template, 60_000);
        assertEquals(3, cache.size());
    }
}