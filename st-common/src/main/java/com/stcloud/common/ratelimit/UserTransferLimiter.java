package com.stcloud.common.ratelimit;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户级传输限速器（服务端强制，不可绕过）
 * <p>
 * 上传：令牌桶门控预签名URL签发 + 单分片在途窗口 + 分片确认释放。限速0=不限速直接放行。
 * 下载：按字节共享令牌桶（阻塞式），多个并发下载共享同一速率上限。
 * <p>
 * 上传与下载使用相互独立的桶（独立锁），互不阻塞；
 * 令牌桶按用户隔离，因此并发分片/并发下载无法叠加突破上限。
 */
@Component
public class UserTransferLimiter {

    private final ConcurrentMap<Long, UploadGate> uploadGates = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, DownloadBucket> downloadBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, UploadPaceBucket> uploadPaceBuckets = new ConcurrentHashMap<>();

    /** 上传窗口：每个用户最多同时持有的未确认分片URL数量（1=逐片，杜绝囤积URL后突发） */
    private static final int UPLOAD_WINDOW = 1;
    /** 预签名URL有效期（毫秒），到期后未确认的在途配额自动回收 */
    private static final long URL_EXPIRY_MS = 5 * 60_000L;

    /**
     * 申请上传一个分片的预签名URL配额（非阻塞）。
     *
     * @param rateBytesPerSec 速率上限(字节/秒)，<=0表示不限速
     * @return allowed=true可签发URL；否则客户端应等待retryAfterMs后重试
     */
    public AcquireResult tryAcquireUpload(Long userId, long chunkBytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0 || chunkBytes <= 0) {
            return AcquireResult.allowed();
        }
        return uploadGates.computeIfAbsent(userId, k -> new UploadGate())
                .tryAcquire(chunkBytes, rateBytesPerSec);
    }

    /** 确认分片上传完成，释放在途配额 */
    public void releaseUpload(Long userId) {
        UploadGate gate = uploadGates.get(userId);
        if (gate != null) {
            gate.release();
        }
    }

    /**
     * 下载按字节限速（阻塞式）。多个并发下载共享同一用户的令牌桶，保证聚合速率不超上限。
     *
     * @param rateBytesPerSec 速率上限(字节/秒)，<=0表示不限速
     */
    public void acquireDownload(Long userId, long bytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return;
        }
        downloadBuckets.computeIfAbsent(userId, k -> new DownloadBucket()).acquire(bytes, rateBytesPerSec);
    }

    /**
     * 上传中转按字节阻塞 pacing（服务端中转接收时节流，保证接收速率≤限速）。
     * 与 presigned 门控(UploadGate)独立桶，互不干扰。
     *
     * @param rateBytesPerSec 速率上限(字节/秒)，<=0表示不限速
     */
    public void acquireUploadPace(Long userId, long bytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0 || bytes <= 0) {
            return;
        }
        uploadPaceBuckets.computeIfAbsent(userId, k -> new UploadPaceBucket()).acquire(bytes, rateBytesPerSec);
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

    /** 上传门控：令牌桶 + 单分片在途窗口 + 过期回收 */
    static final class UploadGate {
        private double tokens;
        private long lastRefillNs;
        private int outstanding;
        private final Deque<Long> issuedMs = new ArrayDeque<>();

        synchronized AcquireResult tryAcquire(long chunkBytes, long rateBytesPerSec) {
            long nowMs = System.currentTimeMillis();
            // 回收已过期但未确认的在途配额
            while (!issuedMs.isEmpty() && issuedMs.peekFirst() + URL_EXPIRY_MS < nowMs) {
                issuedMs.pollFirst();
                if (outstanding > 0) {
                    outstanding--;
                }
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

            // 在途窗口已满：等待已签发分片被确认或过期
            if (outstanding >= UPLOAD_WINDOW) {
                return AcquireResult.retry(500L);
            }
            if (tokens >= chunkBytes) {
                tokens -= chunkBytes;
                outstanding++;
                issuedMs.addLast(nowMs);
                return AcquireResult.allowed();
            }
            // 令牌不足：计算还需等待多久
            double deficit = chunkBytes - tokens;
            long waitMs = (long) (deficit / rateBytesPerSec * 1000.0) + 1L;
            return AcquireResult.retry(Math.min(waitMs, 5000L));
        }

        synchronized void release() {
            if (!issuedMs.isEmpty()) {
                issuedMs.pollFirst();
            }
            if (outstanding > 0) {
                outstanding--;
            }
        }
    }

    /** 上传中转字节令牌桶：阻塞式按字节 pacing，中转接收时节流（复用公共 TokenBucket） */
    static final class UploadPaceBucket {
        private final TokenBucket bucket = new TokenBucket();

        void acquire(long bytes, long rateBytesPerSec) {
            bucket.acquire(bytes, rateBytesPerSec);
        }
    }

    /** 下载字节令牌桶：阻塞式按字节限速，并发下载共享（复用公共 TokenBucket） */
    static final class DownloadBucket {
        private final TokenBucket bucket = new TokenBucket();

        void acquire(long bytes, long rateBytesPerSec) {
            bucket.acquire(bytes, rateBytesPerSec);
        }
    }
}
