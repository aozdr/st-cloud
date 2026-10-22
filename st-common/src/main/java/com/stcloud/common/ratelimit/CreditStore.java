package com.stcloud.common.ratelimit;

/**
 * 跨节点共享的字节信用存储。
 * <p>
 * 只在 mode=redis 时启用；所有实现必须保证「补充 + 扣减」的原子性，
 * 否则多节点会丢失更新导致超发。
 */
public interface CreditStore {

    /**
     * 申请下载/中转 pacing 信用。
     *
     * @param bucketKey     rl:{tenant:user}:down|up
     * @param needBytes     本次希望领取的字节数（通常为 quantum）
     * @param rateBps       当前生效速率（字节/秒）
     * @param capacityBytes 桶容量（字节）
     * @param initBytes     首次初始化时的令牌数（字节）
     * @param ver           配置版本
     * @param ttlMs         空闲过期时间
     */
    CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                      long initBytes, long ver, long ttlMs);

    /**
     * 预签名分片门控：窗口判定 + 令牌扣减在同一个脚本内完成。
     * 窗口已满时直接拒绝且不扣令牌，避免额度丢失。
     */
    CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                              long capacityBytes, long initBytes, long ver, long ttlMs,
                              int windowSize, String chunkKey, long windowTtlMs);

    /** 幂等释放窗口：只有 member 仍存在时才返回 true。 */
    boolean releaseWindow(String windowKey, String chunkKey);
}
