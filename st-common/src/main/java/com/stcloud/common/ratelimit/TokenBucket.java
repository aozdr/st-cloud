package com.stcloud.common.ratelimit;

/**
 * 阻塞式字节令牌桶（公共实现）
 * <p>
 * 供下载限速（DownloadBucket）与上传中转 pacing（UploadPaceBucket）复用：
 * 多个调用方共享同一实例时，聚合速率不超上限。
 * 语义与历史实现保持一致（行为不变）：
 * <ul>
 *   <li>容量 = max(8192, rateBytesPerSec)：桶深至少 8KB，保证低速率下可突发一小段</li>
 *   <li>首次使用时初始化为满桶</li>
 *   <li>令牌按 (nowNs - lastRefillNs) * rateBytesPerSec 持续补充，封顶到容量</li>
 *   <li>令牌不足时按 deficit / rate 计算等待毫秒并 sleep（单次最长 1000ms）</li>
 *   <li>等待期间线程被中断则恢复中断标记并立即返回（不抛异常）</li>
 * </ul>
 */
final class TokenBucket {

    /** 令牌余量（double 避免低速率下精度截断） */
    private double tokens;
    /** 上次补充令牌的纳秒时间戳；0 表示尚未初始化 */
    private long lastRefillNs;

    /**
     * 阻塞式获取 {@code bytes} 字节的令牌。
     *
     * @param bytes           本次需要的字节数（>0）
     * @param rateBytesPerSec 速率上限（字节/秒），>0
     */
    synchronized void acquire(long bytes, long rateBytesPerSec) {
        long capacity = Math.max(8192L, rateBytesPerSec);
        long nowNs = System.nanoTime();
        if (lastRefillNs == 0L) {
            tokens = capacity;
            lastRefillNs = nowNs;
        }
        while (true) {
            //距离上次补充token时间
            double elapsedSec = (nowNs - lastRefillNs) / 1_000_000_000.0;
            // 本次等待上限放宽到 max(capacity, bytes)：避免"申请量 > 桶深"时
            // tokens 被封顶到 capacity 永远达不到申请量而死循环；空闲突发仍受 capacity 限制。
            double cap = Math.max(capacity, (double) bytes);
            tokens = Math.min(cap, tokens + elapsedSec * rateBytesPerSec);
            lastRefillNs = nowNs;
            if (tokens >= bytes) {
                tokens -= bytes;
                return;
            }
            double deficit = bytes - tokens;
            long waitMs = (long) (deficit / rateBytesPerSec * 1000.0) + 1L;
            try {
                Thread.sleep(Math.min(waitMs, 1000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            nowNs = System.nanoTime();
        }
    }
}
