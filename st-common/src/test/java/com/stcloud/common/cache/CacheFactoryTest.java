package com.stcloud.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CacheFactory 单元测试（TASK-003）：默认（未启用 Redis）返回内存缓存；
 * 启用但无 RedisTemplate 回退内存；启用且有 RedisTemplate 返回 Redis 缓存。
 */
@DisplayName("缓存工厂测试")
class CacheFactoryTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<RedisTemplate<String, Object>> provider(RedisTemplate<String, Object> template) {
        ObjectProvider<RedisTemplate<String, Object>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    @Test
    @DisplayName("未启用 Redis - 返回内存缓存")
    void disabledReturnsMemoryCache() {
        CacheFactory factory = new CacheFactory(false, provider(null));
        assertTrue(factory.create(30_000) instanceof TtlCache);
    }

    @Test
    @DisplayName("启用但无 RedisTemplate - 回退内存缓存")
    void enabledWithoutRedisTemplateFallsBackToMemory() {
        CacheFactory factory = new CacheFactory(true, provider(null));
        assertTrue(factory.create(30_000) instanceof TtlCache);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("启用且有 RedisTemplate - 返回 Redis 缓存")
    void enabledWithRedisTemplateReturnsRedisCache() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        CacheFactory factory = new CacheFactory(true, provider(template));
        assertTrue(factory.create(30_000) instanceof RedisTtlCache);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("未启用时忽略 RedisTemplate - 返回内存缓存")
    void disabledIgnoresRedisTemplate() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        CacheFactory factory = new CacheFactory(false, provider(template));
        assertTrue(factory.create(30_000) instanceof TtlCache);
    }
}