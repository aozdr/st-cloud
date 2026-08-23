package com.stcloud.share;

import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.cache.TtlCache;
import com.stcloud.common.sysconfig.SysConfigService;
import com.stcloud.share.service.ShareBruteForceGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 分享防爆破守卫单元测试：单码维度与 IP 维度达阈值触发锁定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShareBruteForceGuardTest {

    @Mock
    private SysConfigService configService;

    @Mock
    private CacheFactory cacheFactory;

    private ShareBruteForceGuard newGuard() {
        when(cacheFactory.create(anyLong())).thenReturn(new TtlCache(2 * 60 * 60 * 1000L));
        when(configService.getBool("share.brute_force.captchaEnabled", true)).thenReturn(false);
        when(configService.getInt("share.brute_force.captchaThreshold", 3)).thenReturn(3);
        when(configService.getInt("share.brute_force.codeWindowMs", 300_000)).thenReturn(300_000);
        when(configService.getInt("share.brute_force.codeLockMs", 900_000)).thenReturn(900_000);
        when(configService.getInt("share.brute_force.ipWindowMs", 600_000)).thenReturn(600_000);
        when(configService.getInt("share.brute_force.ipLockMs", 1_800_000)).thenReturn(1_800_000);
        return new ShareBruteForceGuard(configService, cacheFactory);
    }

    @Test
    @DisplayName("IP 失败达阈值触发锁定，不同 IP 不互锁")
    void ipLockedAfterThreshold() {
        when(configService.getInt("share.brute_force.maxFailPerCode", 5)).thenReturn(100);
        when(configService.getInt("share.brute_force.maxFailPerIp", 20)).thenReturn(2);
        ShareBruteForceGuard guard = newGuard();
        guard.recordFailure("1.1.1.1", "CODE-A");
        assertFalse(guard.isLocked("1.1.1.1", "CODE-A"));
        guard.recordFailure("1.1.1.1", "CODE-B");
        assertTrue(guard.isLocked("1.1.1.1", "CODE-A"));
        assertFalse(guard.isLocked("2.2.2.2", "CODE-A"));
    }

    @Test
    @DisplayName("单码失败达阈值触发锁定，不影响其它分享码")
    void codeLockedAfterThreshold() {
        when(configService.getInt("share.brute_force.maxFailPerCode", 5)).thenReturn(2);
        when(configService.getInt("share.brute_force.maxFailPerIp", 20)).thenReturn(100);
        ShareBruteForceGuard guard = newGuard();
        guard.recordFailure("2.2.2.2", "CODE-X");
        assertFalse(guard.isLocked(null, "CODE-X"));
        guard.recordFailure("2.2.2.2", "CODE-X");
        assertTrue(guard.isLocked(null, "CODE-X"));
        assertFalse(guard.isLocked(null, "CODE-Y"));
    }
}
