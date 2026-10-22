package com.stcloud.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限速执行配置。
 * <p>
 * mode=local 保持单节点进程内令牌桶；mode=redis 启用跨节点共享信用桶，
 * 本地桶只做 8KB 平滑，Redis 负责全局额度扣减。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stcloud.rate-limit")
public class RateLimitProperties {

    /** local | redis */
    private String mode = "local";
    /** quantum 覆盖的时长(毫秒)：本地每次向 Redis 申请的信用 */
    private long quantumMs = 100L;
    private long quantumMinBytes = 64 * 1024L;
    private long quantumMaxBytes = 1024 * 1024L;
    /** 共享桶空闲 TTL(毫秒) */
    private long keyTtlMs = 10 * 60_000L;
    /** Redis 故障时是否 fail-open（本地按 rate 继续，最坏 N 倍） */
    private boolean failOpen = true;
    /** 配置失效广播频道 */
    private String invalidationChannel = "stcloud:rate-limit:evict";
    /** 上传在途窗口大小 */
    private int uploadWindow = 1;
    /** 上传在途 URL 有效期(毫秒) */
    private long uploadWindowTtlMs = 5 * 60_000L;
}
