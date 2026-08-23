package com.stcloud.share.service;

import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import com.stcloud.common.sysconfig.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 分享防爆破守卫（S-14 分享防枚举）。
 * <p>
 * 基于 {@link CacheFactory}（多实例 Redis 一致、单实例内存兜底）记录提取码失败次数，
 * 达到阈值后锁定分享码 / IP，并在失败数达到验证码阈值时要求图形验证码。
 * 所有阈值/窗口/锁定时长从全局配置表读取（share.brute_force.*），可后台修改。
 */
@Slf4j
@Service
public class ShareBruteForceGuard {

    // 统一计数器缓存：键内自含时间戳，值自会过期窗口/锁定，TTL 仅防止内存泄漏
    private static final long MAX_CACHE_TTL_MILLIS = 2 * 60 * 60 * 1000L;

    private final SysConfigService configService;
    private final Cache cache;

    public ShareBruteForceGuard(SysConfigService configService, CacheFactory cacheFactory) {
        this.configService = configService;
        this.cache = cacheFactory.create(MAX_CACHE_TTL_MILLIS);
    }

    /** 失败窗口计数：count 次数 + windowStart 窗口起始时间戳 */
    private static final class WindowCount {
        private int count;
        private final long windowStart;

        WindowCount(int count, long windowStart) {
            this.count = count;
            this.windowStart = windowStart;
        }
    }

    /** 分享码是否处于锁定（单码或 IP 任一命中） */
    public boolean isLocked(String ip, String shareCode) {
        if (isLockedUntil("share:brute:lock:code:" + shareCode)) {
            return true;
        }
        return ip != null && isLockedUntil("share:brute:lock:ip:" + ip);
    }

    /** 当前分享码失败次数（窗口过期自动归零） */
    public int failCountForCode(String shareCode) {
        return currentCount("share:brute:code:" + shareCode, codeWindowMs());
    }

    /** 是否需要验证码：启用验证码且失败次数达阈值 */
    public boolean needsCaptcha(String shareCode) {
        return captchaEnabled() && failCountForCode(shareCode) >= captchaThreshold();
    }

    /** 记录一次提取码失败：累加单码 + IP 窗口，达阈值写入锁定时间戳 */
    public void recordFailure(String ip, String shareCode) {
        WindowCount code = incr("share:brute:code:" + shareCode, codeWindowMs());
        if (code.count >= maxFailPerCode()) {
            cache.put("share:brute:lock:code:" + shareCode, System.currentTimeMillis() + codeLockMs());
            log.warn("分享码防爆破触发锁定: shareCode={}, fail={}", shareCode, code.count);
        }
        if (ip != null) {
            WindowCount ipc = incr("share:brute:ip:" + ip, ipWindowMs());
            if (ipc.count >= maxFailPerIp()) {
                cache.put("share:brute:lock:ip:" + ip, System.currentTimeMillis() + ipLockMs());
                log.warn("IP 防爆破触发锁定: ip={}, fail={}", ip, ipc.count);
            }
        }
    }

    /** 验证码校验失败：仅累加 IP 失败（验证码本身错误不累加单码，避免误伤） */
    public void recordCaptchaFailure(String ip) {
        if (ip != null) {
            incr("share:brute:ip:" + ip, ipWindowMs());
        }
    }

    /** 提取码校验成功：清除单码失败计数与锁定 */
    public void clearFailure(String shareCode) {
        cache.removeByPrefix("share:brute:code:" + shareCode);
        cache.removeByPrefix("share:brute:lock:code:" + shareCode);
    }

    private WindowCount incr(String key, long windowMs) {
        WindowCount wc = current(key, windowMs);
        wc.count++;
        cache.put(key, wc);
        return wc;
    }

    private int currentCount(String key, long windowMs) {
        return current(key, windowMs).count;
    }

    private WindowCount current(String key, long windowMs) {
        Object o = cache.get(key);
        if (o instanceof WindowCount wc) {
            if (System.currentTimeMillis() - wc.windowStart > windowMs) {
                return new WindowCount(0, System.currentTimeMillis());
            }
            return wc;
        }
        return new WindowCount(0, System.currentTimeMillis());
    }

    private boolean isLockedUntil(String key) {
        Object o = cache.get(key);
        return o instanceof Long until && System.currentTimeMillis() < until;
    }

    private int maxFailPerCode() {
        return configService.getInt("share.brute_force.maxFailPerCode", 5);
    }

    private long codeWindowMs() {
        return configService.getInt("share.brute_force.codeWindowMs", 300_000);
    }

    private long codeLockMs() {
        return configService.getInt("share.brute_force.codeLockMs", 900_000);
    }

    private int maxFailPerIp() {
        return configService.getInt("share.brute_force.maxFailPerIp", 20);
    }

    private long ipWindowMs() {
        return configService.getInt("share.brute_force.ipWindowMs", 600_000);
    }

    private long ipLockMs() {
        return configService.getInt("share.brute_force.ipLockMs", 1_800_000);
    }

    private boolean captchaEnabled() {
        return configService.getBool("share.brute_force.captchaEnabled", true);
    }

    private int captchaThreshold() {
        return configService.getInt("share.brute_force.captchaThreshold", 3);
    }
}
