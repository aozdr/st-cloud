package com.stcloud.common.ratelimit;

import com.stcloud.common.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户级传输限速器。
 * <p>
 * mode=local：进程内令牌桶，仅单节点正确（历史行为）。
 * mode=redis：本地桶只做 8KB 平滑，额度由 Redis 原子扣减，多节点聚合不超上限。
 * 上传与下载使用独立桶，上传门控额外带「在途窗口」，释放按 chunkKey 幂等。
 * Redis 故障时上传门控与释放回落到本地上限，下载由 CreditBucket 降级，均不中断传输。
 */
@Component
public class UserTransferLimiter {

    private static final RateLimitProperties DEFAULT_PROPERTIES = new RateLimitProperties();
    private static final long IDLE_EVICT_MS = 30 * 60_000L;

    private final ConcurrentMap<Long, UploadGate> uploadGates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DirectionBuckets> buckets = new ConcurrentHashMap<>();
    /** 客户端自设限速的本地非阻塞门控（按用户），不写入共享桶。 */
    private final ConcurrentMap<String, TokenBucket> clientPaceBuckets = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private RateLimitProperties properties;
    @Autowired(required = false)
    private CreditStore creditStore;
    @Autowired(required = false)
    private SpeedLimitCache speedLimitCache;
    @Autowired(required = false)
    private RateLimitStats stats;

    /** 便于旧调用方与单测，chunkKey 为空时无法幂等释放。 */
    public AcquireResult tryAcquireUpload(Long userId, long chunkBytes, long rateBytesPerSec) {
        return tryAcquireUpload(userId, null, chunkBytes, rateBytesPerSec, 0L);
    }

    public AcquireResult tryAcquireUpload(Long userId, String chunkKey, long chunkBytes, long rateBytesPerSec) {
        return tryAcquireUpload(userId, chunkKey, chunkBytes, rateBytesPerSec, 0L);
    }

    public AcquireResult tryAcquireUpload(Long userId, String chunkKey, long chunkBytes,
                                          long rateBytesPerSec, long ver) {
        if (rateBytesPerSec <= 0 || chunkBytes <= 0) {
            return AcquireResult.allowed();
        }
        if (redisMode()) {
            try {
                long capacity = Math.max(chunkBytes, rateBytesPerSec);
                CreditGrant grant = creditStore.acquireUpload(bucketKey(userId, "up"), windowKey(userId),
                        chunkBytes, rateBytesPerSec, capacity, capacity, ver, props().getKeyTtlMs(),
                        Math.max(0, props().getUploadWindow()), chunkKey == null ? "" : chunkKey,
                        props().getUploadWindowTtlMs());
                if (grant.stale()) {
                    // 配置版本过期：失效本机缓存，让客户端重试时按新规则签发
                    evictCache();
                    return AcquireResult.retry(200L);
                }
                if (stats != null) {
                    if (grant.granted()) {
                        stats.granted();
                    } else {
                        stats.denied();
                    }
                }
                return grant.granted() ? AcquireResult.allowed() : AcquireResult.retry(grant.waitMs());
            } catch (RuntimeException e) {
                if (!props().isFailOpen()) {
                    throw e;
                }
                // 消息链路无法共享时退化为本节点窗口，保证 confirm/签发不中断
                if (stats != null) {
                    stats.storeError();
                    stats.degraded();
                }
                return uploadGates.computeIfAbsent(userId, k -> new UploadGate())
                        .tryAcquire(chunkKey, chunkBytes, rateBytesPerSec, props());
            }
        }
        return uploadGates.computeIfAbsent(userId, k -> new UploadGate())
                .tryAcquire(chunkKey, chunkBytes, rateBytesPerSec, props());
    }

    /** 兼容旧调用：释放最早签发的在途配额。 */
    public void releaseUpload(Long userId) {
        releaseUpload(userId, null);
    }

    /** 确认分片上传完成，按 chunkKey 幂等释放（重复 confirm 不会释放其他分片）。 */
    public void releaseUpload(Long userId, String chunkKey) {
        if (redisMode()) {
            try {
                if (chunkKey != null && !chunkKey.isEmpty()) {
                    creditStore.releaseWindow(windowKey(userId), chunkKey);
                }
                return;
            } catch (RuntimeException e) {
                if (!props().isFailOpen()) {
                    throw e;
                }
                if (stats != null) {
                    stats.storeError();
                    stats.degraded();
                }
                // 继续尝试释放本地备用窗口，避免降级期间的配额泄漏
            }
        }
        UploadGate gate = uploadGates.get(userId);
        if (gate != null) {
            gate.release(chunkKey);
        }
    }

    public void acquireDownload(Long userId, long bytes, long rateBytesPerSec) {
        acquireDownload(userId, bytes, rateBytesPerSec, 0L, null);
    }

    public void acquireDownload(Long userId, long bytes, long rateBytesPerSec, long ver) {
        acquireDownload(userId, bytes, rateBytesPerSec, ver, null);
    }

    /**
     * 下载按字节限速（阻塞式）。refreshed 在配置版本过期时回调，
     * 让在途下载按新速率继续，而不是整段沿用旧值。
     */
    public void acquireDownload(Long userId, long bytes, long rateBytesPerSec, long ver,
                                LimitSupplier refreshed) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return;
        }
        DirectionBuckets holder = bucket(userId, "down");
        if (redisMode()) {
            holder.credit.acquire(bytes, rateBytesPerSec, quantum(rateBytesPerSec), creditStore,
                    bucketKey(userId, "down"), ver, props().getKeyTtlMs(), props().isFailOpen(),
                    compose(refreshed), this::evictCache);
        } else {
            holder.local.acquire(bytes, rateBytesPerSec);
        }
    }

    public void acquireUploadPace(Long userId, long bytes, long rateBytesPerSec) {
        acquireUploadPace(userId, bytes, rateBytesPerSec, 0L, null);
    }

    public void acquireUploadPace(Long userId, long bytes, long rateBytesPerSec, long ver) {
        acquireUploadPace(userId, bytes, rateBytesPerSec, ver, null);
    }

    /** 上传中转按字节阻塞 pacing；refreshed 用于配置版本过期时自愈。 */
    public void acquireUploadPace(Long userId, long bytes, long rateBytesPerSec, long ver,
                                  LimitSupplier refreshed) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return;
        }
        DirectionBuckets holder = bucket(userId, "up-pace");
        if (redisMode()) {
            holder.credit.acquire(bytes, rateBytesPerSec, quantum(rateBytesPerSec), creditStore,
                    bucketKey(userId, "up"), ver, props().getKeyTtlMs(), props().isFailOpen(),
                    compose(refreshed), this::evictCache);
        } else {
            holder.local.acquire(bytes, rateBytesPerSec);
        }
    }

    /**
     * 客户端自设限速只在本节点执行，不写入共享桶：
     * 共享桶必须以服务端 rate 为准，否则不同 clientLimit 会让同一 ver 的 rate 抖动。
     */
    public void acquireClientLimit(Long userId, long bytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return;
        }
        bucket(userId, "client-pace").local.acquire(bytes, rateBytesPerSec);
    }

    /**
     * 预签名门控前的客户端限速检查（非阻塞）。
     * 客户端限速只在本节点生效，避免把不同 clientLimit 写进共享桶导致 rate 抖动。
     */
    public AcquireResult tryAcquireClientLimit(Long userId, long bytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return AcquireResult.allowed();
        }
        long waitMs = clientPaceBuckets.computeIfAbsent(tag(userId), k -> new TokenBucket())
                .tryAcquire(bytes, rateBytesPerSec);
        return waitMs <= 0L ? AcquireResult.allowed() : AcquireResult.retry(Math.min(waitMs, 5000L));
    }

    /** 定期回收空闲桶，避免用户量增长后 ConcurrentHashMap 无界膨胀。 */
    @Scheduled(fixedDelayString = "${stcloud.rate-limit.evict-interval-ms:300000}")
    public void evictIdleBuckets() {
        long now = System.currentTimeMillis();
        uploadGates.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMs() > IDLE_EVICT_MS);
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMs() > IDLE_EVICT_MS);
        clientPaceBuckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMs() > IDLE_EVICT_MS);
    }

    private LimitSupplier compose(LimitSupplier refreshed) {
        if (refreshed == null) {
            return null;
        }
        return () -> {
            CreditLimit fresh = refreshed.get();
            return new CreditLimit(fresh.rateBytes(), fresh.ver(), quantum(fresh.rateBytes()));
        };
    }

    private RateLimitProperties props() {
        return properties != null ? properties : DEFAULT_PROPERTIES;
    }

    private boolean redisMode() {
        return creditStore != null && "redis".equalsIgnoreCase(props().getMode());
    }

    private long quantum(long rateBytesPerSec) {
        long q = (long) (rateBytesPerSec * (double) props().getQuantumMs() / 1000.0);
        q = Math.max(props().getQuantumMinBytes(), Math.min(props().getQuantumMaxBytes(), q));
        return Math.max(8192L, q);
    }

    private DirectionBuckets bucket(Long userId, String direction) {
        // 本地 key 同样带租户，避免不同租户同 id 用户共享桶
        return buckets.computeIfAbsent(tag(userId) + ":" + direction, k -> new DirectionBuckets(stats));
    }

    /** key 带 hash tag，保证同一用户的桶与窗口落在同一 Redis Cluster slot。 */
    private String tag(Long userId) {
        Long tenantId = TenantContext.getTenantId();
        return (tenantId != null ? tenantId : 0L) + ":" + userId;
    }

    private String bucketKey(Long userId, String direction) {
        return "rl:{" + tag(userId) + "}:" + direction;
    }

    private String windowKey(Long userId) {
        return "rl:{" + tag(userId) + "}:win";
    }

    private void evictCache() {
        if (speedLimitCache != null) {
            speedLimitCache.evict();
        }
    }

    /** 上传门控结果 */
    public static final class AcquireResult {
        private final boolean allowed;
        private final long retryAfterMs;

        private AcquireResult(boolean allowed, long retryAfterMs) {
            this.allowed = allowed;
            this.retryAfterMs = retryAfterMs;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }

        public static AcquireResult allowed() {
            return new AcquireResult(true, 0L);
        }

        public static AcquireResult retry(long retryAfterMs) {
            return new AcquireResult(false, Math.max(retryAfterMs, 50L));
        }
    }

    /** 每用户每方向的本地桶组合：local 模式用 TokenBucket，redis 模式用 CreditBucket。 */
    static final class DirectionBuckets {
        final TokenBucket local = new TokenBucket();
        final CreditBucket credit;

        DirectionBuckets(RateLimitStats stats) {
            this.credit = new CreditBucket(stats);
        }

        long lastAccessMs() {
            return Math.max(local.lastAccessMs(), credit.lastAccessMs());
        }
    }

    /** 已签发的在途配额：member 为 uploadId:chunkIndex，过期后自动回收。 */
    private static final class Issued {
        final String key;
        final long expireAtMs;

        Issued(String key, long expireAtMs) {
            this.key = key;
            this.expireAtMs = expireAtMs;
        }
    }

    /** 上传门控：令牌桶 + 在途窗口 + 过期回收（单节点实现）。 */
    static final class UploadGate {
        private double tokens;
        private long lastRefillNs;
        private long lastAccessMs = System.currentTimeMillis();
        private final Deque<Issued> issued = new ArrayDeque<>();

        synchronized AcquireResult tryAcquire(String chunkKey, long chunkBytes,
                                             long rateBytesPerSec, RateLimitProperties props) {
            long nowMs = System.currentTimeMillis();
            while (!issued.isEmpty() && issued.peekFirst().expireAtMs < nowMs) {
                issued.pollFirst();
            }
            long nowNs = System.nanoTime();
            double capacity = Math.max(chunkBytes, (double) rateBytesPerSec);
            if (lastRefillNs == 0L) {
                tokens = capacity;
                lastRefillNs = nowNs;
            }
            double elapsedSec = (nowNs - lastRefillNs) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * rateBytesPerSec);
            lastRefillNs = nowNs;
            lastAccessMs = nowMs;

            // window<=0 与 Redis 脚本一致：不限在途数量
            int window = props.getUploadWindow() > 0 ? props.getUploadWindow() : Integer.MAX_VALUE;
            if (issued.size() >= window) {
                return AcquireResult.retry(500L);
            }
            if (tokens >= chunkBytes) {
                tokens -= chunkBytes;
                issued.addLast(new Issued(chunkKey, nowMs + props.getUploadWindowTtlMs()));
                return AcquireResult.allowed();
            }
            double deficit = chunkBytes - tokens;
            long waitMs = (long) (deficit / rateBytesPerSec * 1000.0) + 1L;
            return AcquireResult.retry(Math.min(waitMs, 5000L));
        }

        synchronized void release(String chunkKey) {
            if (chunkKey == null || chunkKey.isEmpty()) {
                if (!issued.isEmpty()) {
                    issued.pollFirst();
                }
                return;
            }
            // 只有仍然存在的 member 才释放；重复 confirm 不会误伤其他分片
            issued.removeIf(item -> chunkKey.equals(item.key));
        }

        synchronized long lastAccessMs() {
            return lastAccessMs;
        }
    }
}
