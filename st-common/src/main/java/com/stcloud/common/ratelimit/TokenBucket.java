package com.stcloud.common.ratelimit;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 阻塞式字节令牌桶（单节点实现）。
 * <p>
 * 与历史行为一致：容量 = max(8192, rate)，首次满桶，按纳秒补充，令牌不足时按缺口睡眠。
 * 关键修复：睡眠移到锁外，避免一个等待线程持锁阻塞同一用户的其他流。
 */
final class TokenBucket {

    private final ReentrantLock lock = new ReentrantLock();
    /** 令牌余量（double 避免低速率下精度截断） */
    private double tokens;
    /** 上次补充令牌的纳秒时间戳；0 表示尚未初始化 */
    private long lastRefillNs;
    /** 最近一次访问时间，用于空闲回收 */
    private long lastAccessMs = System.currentTimeMillis();

    void acquire(long bytes, long rateBytesPerSec) {
        while (true) {
            long waitMs;
            lock.lock();
            try {
                long capacity = Math.max(8192L, rateBytesPerSec);
                long nowNs = System.nanoTime();
                if (lastRefillNs == 0L) {
                    tokens = capacity;
                    lastRefillNs = nowNs;
                }
                double elapsedSec = (nowNs - lastRefillNs) / 1_000_000_000.0;
                // 等待上限放宽到 max(capacity, bytes)：避免申请量 > 桶深时永远达不到而死循环
                double cap = Math.max(capacity, (double) bytes);
                tokens = Math.min(cap, tokens + elapsedSec * rateBytesPerSec);
                lastRefillNs = nowNs;
                lastAccessMs = System.currentTimeMillis();
                if (tokens >= bytes) {
                    tokens -= bytes;
                    return;
                }
                double deficit = bytes - tokens;
                waitMs = (long) (deficit / rateBytesPerSec * 1000.0) + 1L;
            } finally {
                lock.unlock();
            }
            try {
                Thread.sleep(Math.min(waitMs, 1000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 非阻塞获取：成功扣减并返回 0；不足返回建议等待毫秒。
     * 容量取 max(8192, rate, 本次 bytes)，保证大分片能一次性放行、空闲后可突发。
     */
    long tryAcquire(long bytes, long rateBytesPerSec) {
        lock.lock();
        try {
            long capacity = Math.max(Math.max(8192L, rateBytesPerSec), bytes);
            long nowNs = System.nanoTime();
            if (lastRefillNs == 0L) {
                tokens = capacity;
                lastRefillNs = nowNs;
            }
            double elapsedSec = (nowNs - lastRefillNs) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * rateBytesPerSec);
            lastRefillNs = nowNs;
            lastAccessMs = System.currentTimeMillis();
            if (tokens >= bytes) {
                tokens -= bytes;
                return 0L;
            }
            return (long) ((bytes - tokens) / rateBytesPerSec * 1000.0) + 1L;
        } finally {
            lock.unlock();
        }
    }

    long lastAccessMs() {
        return lastAccessMs;
    }
}
