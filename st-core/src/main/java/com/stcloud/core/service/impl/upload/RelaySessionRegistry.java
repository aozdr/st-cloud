package com.stcloud.core.service.impl.upload;

import com.stcloud.core.config.UploadRelayConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 中转上传会话配置的跨节点登记：多节点部署时 init 不创建本地临时文件，
 * 首个 relay-chunk 由 Nginx 按 uploadId 粘滞到某节点后再惰性创建。
 * 只在 mode=redis 时启用；所有 Redis 操作失败都降级为「按本地会话继续」，
 * 由调用方决定是回退单机行为还是返回 409。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "stcloud.rate-limit.mode", havingValue = "redis")
public class RelaySessionRegistry {

    private static final String PREFIX = "relay:cfg:";

    private final StringRedisTemplate redis;
    private final UploadRelayConfig config;

    public RelaySessionRegistry(StringRedisTemplate redis, UploadRelayConfig config) {
        this.redis = redis;
        this.config = config;
    }

    /** 登记一次上传的限速与小块大小；失败返回 false，调用方可回落本地会话。 */
    public boolean register(String uploadId, long rateBytes, long relayChunkSize) {
        try {
            redis.opsForValue().set(key(uploadId), rateBytes + ":" + relayChunkSize,
                    config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException e) {
            log.warn("中转配置登记失败，回落本地会话：uploadId={}, error={}", uploadId, e.getMessage());
            return false;
        }
    }

    /** 活动续期；失败不影响主流程。 */
    public void refresh(String uploadId) {
        try {
            redis.expire(key(uploadId), config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("中转配置续期失败：uploadId={}, error={}", uploadId, e.getMessage());
        }
    }

    /** 读取配置；不存在或 Redis 不可用都返回 null，由调用方返回明确的 409。 */
    public RelayConfig get(String uploadId) {
        try {
            String value = redis.opsForValue().get(key(uploadId));
            if (value == null) {
                return null;
            }
            int split = value.indexOf(':');
            if (split <= 0) {
                return null;
            }
            return new RelayConfig(Long.parseLong(value.substring(0, split)),
                    Long.parseLong(value.substring(split + 1)));
        } catch (RuntimeException e) {
            log.warn("中转配置读取失败：uploadId={}, error={}", uploadId, e.getMessage());
            return null;
        }
    }

    public void remove(String uploadId) {
        try {
            redis.delete(key(uploadId));
        } catch (RuntimeException e) {
            log.warn("中转配置清理失败：uploadId={}, error={}", uploadId, e.getMessage());
        }
    }

    private String key(String uploadId) {
        return PREFIX + uploadId;
    }

    /** 单次中转上传的稳定参数。 */
    public record RelayConfig(long rateBytes, long relayChunkSize) {
    }
}
