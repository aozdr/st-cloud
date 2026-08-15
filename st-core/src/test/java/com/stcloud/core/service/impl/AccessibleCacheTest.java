package com.stcloud.core.service.impl;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.mapper.FileNodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * validateAccessible 可访问性缓存单测（TASK-005）。
 * 纯 Mockito，不依赖 Spring 上下文：注入 mock FileNodeMapper 验证缓存命中、失效重算与禁止路径。
 */
class AccessibleCacheTest {

    private FileServiceImpl newService(FileNodeMapper mapper) {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileNodeMapper", mapper);
        return service;
    }

    @Test
    void cacheHitSkipsSecondSql() {
        FileNodeMapper mapper = mock(FileNodeMapper.class);
        when(mapper.countInaccessibleAncestors(100L)).thenReturn(0L);
        FileServiceImpl service = newService(mapper);

        service.validateAccessible(100L);
        service.validateAccessible(100L);

        // 二次访问命中缓存，不再执行祖先链递归 SQL
        verify(mapper, times(1)).countInaccessibleAncestors(100L);
    }

    @Test
    void invalidateRecomputes() {
        FileNodeMapper mapper = mock(FileNodeMapper.class);
        when(mapper.countInaccessibleAncestors(100L)).thenReturn(0L, 1L);
        FileServiceImpl service = newService(mapper);

        service.validateAccessible(100L);
        // 结构变更后失效，再次访问重新计算（本次模拟祖先被回收 → 不可访问）
        service.invalidateAccessible(100L);
        assertThrows(BusinessException.class, () -> service.validateAccessible(100L));

        verify(mapper, times(2)).countInaccessibleAncestors(100L);
    }

    @Test
    void inaccessibleThrowsForbiddenFromCache() {
        FileNodeMapper mapper = mock(FileNodeMapper.class);
        when(mapper.countInaccessibleAncestors(200L)).thenReturn(1L);
        FileServiceImpl service = newService(mapper);

        assertThrows(BusinessException.class, () -> service.validateAccessible(200L));
        // 第一次计算并缓存 false；第二次直接命中缓存抛 FORBIDDEN，不查库
        assertThrows(BusinessException.class, () -> service.validateAccessible(200L));
        verify(mapper, times(1)).countInaccessibleAncestors(200L);
    }

    @Test
    void nullNodeIdNoOp() {
        FileNodeMapper mapper = mock(FileNodeMapper.class);
        FileServiceImpl service = newService(mapper);
        service.validateAccessible(null);
        verify(mapper, never()).countInaccessibleAncestors(any());
    }
}
