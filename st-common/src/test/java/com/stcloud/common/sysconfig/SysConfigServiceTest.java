package com.stcloud.common.sysconfig;

import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.entity.SysConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.mapper.SysConfigMapper;
import com.stcloud.common.response.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局配置服务单元测试：默认值、DB 读取与缓存、布尔解析、白名单限制、更新与缓存失效。
 */
@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    private static final String KEY = "share.brute_force.maxFailPerCode";
    private static final String CACHE_KEY = "sys_config:" + KEY;

    @Mock
    private SysConfigMapper mapper;

    @Mock
    private CacheFactory cacheFactory;

    @Mock
    private Cache cache;

    private SysConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        when(cacheFactory.create(anyLong())).thenReturn(cache);
        service = new SysConfigServiceImpl(mapper, cacheFactory);
        service.initCache();
    }

    private SysConfig cfg(String value) {
        SysConfig c = new SysConfig();
        c.setConfigKey(KEY);
        c.setConfigValue(value);
        c.setEnabled(1);
        return c;
    }

    @Test
    @DisplayName("DB 无记录时返回默认值")
    void defaultValueWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(5, service.getInt(KEY, 5));
    }

    @Test
    @DisplayName("命中 DB 时返回配置值且缓存")
    void readAndCache() {
        when(mapper.selectOne(any())).thenReturn(cfg("5"));
        assertEquals(5, service.getInt(KEY, 99));
        verify(cache).put(CACHE_KEY, "5");
    }

    @Test
    @DisplayName("缓存命中时不重复查库")
    void cacheHitSkipsDb() {
        when(cache.get(CACHE_KEY)).thenReturn("7");
        assertEquals(7, service.getInt(KEY, 5));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("布尔配置解析")
    void parseBool() {
        when(mapper.selectOne(any())).thenReturn(cfg("true"));
        assertTrue(service.getBool("share.brute_force.captchaEnabled", false));
    }

    @Test
    @DisplayName("非白名单键拒绝更新")
    void updateRejectsNonWhitelist() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("other.key", "x"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        verify(mapper, never()).insert(any(SysConfig.class));
    }

    @Test
    @DisplayName("白名单键更新时插入新配置并清缓存")
    void updateWhitelistInsertsAndEvicts() {
        when(mapper.selectOne(any())).thenReturn(null);
        service.update(KEY, "6");
        verify(mapper).insert(org.mockito.ArgumentMatchers.<SysConfig>argThat(
                c -> c != null && KEY.equals(c.getConfigKey()) && "6".equals(c.getConfigValue())));
        verify(cache).removeByPrefix("sys_config:");
    }
}
