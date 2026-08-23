package com.stcloud.share.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.stcloud.common.cache.Cache;
import com.stcloud.common.cache.CacheFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 分享访问图形验证码（S-14 分享防枚举）。基于 Hutool LineCaptcha 生成图片，答案存缓存，一次性校验。
 */
@Service
public class ShareCaptchaService {

    private static final String PREFIX = "share:captcha:";
    private static final long TTL_MILLIS = 120_000L;
    private static final int CODE_LENGTH = 4;
    private static final int LINE_COUNT = 60;

    private final Cache cache;

    public ShareCaptchaService(CacheFactory cacheFactory) {
        this.cache = cacheFactory.create(TTL_MILLIS);
    }

    /** 生成验证码，返回 captchaId；图片 base64 通过 generateImage 读取 */
    public CaptchaIssue issue() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, CODE_LENGTH, LINE_COUNT);
        String id = UUID.randomUUID().toString();
        cache.put(PREFIX + id, captcha.getCode());
        return new CaptchaIssue(id, captcha.getImageBase64());
    }

    /** 校验验证码：成功即一次销毁（同一 captchaId 不可复用） */
    public boolean verify(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            return false;
        }
        Object stored = cache.get(PREFIX + captchaId);
        if (!(stored instanceof String)) {
            return false;
        }
        cache.removeByPrefix(PREFIX + captchaId);
        return ((String) stored).equalsIgnoreCase(captchaCode.trim());
    }

    /** 验证码下发结果：captchaId + imageBase64（含 data URI 前缀） */
    public record CaptchaIssue(String captchaId, String imageBase64) {
    }
}
