package com.stcloud.share;

import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.share.service.ShareCaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享验证码服务单元测试：校验大小写不敏感、一次有效销毁、空/过期分支。
 */
@ExtendWith(MockitoExtension.class)
class ShareCaptchaServiceTest {

    private static final String PREFIX = "share:captcha:";
    private static final String ID = "test-id-1";
    private static final String ANSWER = "Ab1c";

    @Mock
    private CacheFactory cacheFactory;

    @Mock
    private Cache cache;

    private ShareCaptchaService service;

    @BeforeEach
    void setUp() {
        when(cacheFactory.create(anyLong())).thenReturn(cache);
        service = new ShareCaptchaService(cacheFactory);
    }

    @Test
    @DisplayName("验证码大小写不敏感且一次性销毁")
    void verifyCorrectCaseInsensitiveAndConsume() {
        when(cache.get(PREFIX + ID)).thenReturn(ANSWER);
        assertTrue(service.verify(ID, "ab1C"));
        verify(cache).removeByPrefix(PREFIX + ID);
    }

    @Test
    @DisplayName("验证码错误返回 false")
    void verifyWrong() {
        when(cache.get(PREFIX + ID)).thenReturn(ANSWER);
        assertFalse(service.verify(ID, "xxxx"));
    }

    @Test
    @DisplayName("验证码空参返回 false")
    void verifyBlank() {
        assertFalse(service.verify(null, "x"));
        assertFalse(service.verify(ID, null));
    }

    @Test
    @DisplayName("验证码不存在或已过期返回 false")
    void verifyUnknown() {
        when(cache.get(PREFIX + ID)).thenReturn(null);
        assertFalse(service.verify(ID, "x"));
    }
}
