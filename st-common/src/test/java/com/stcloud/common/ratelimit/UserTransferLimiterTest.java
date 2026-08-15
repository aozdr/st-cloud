package com.stcloud.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * UserTransferLimiter 重构（双桶复用公共 TokenBucket）后的行为不变验证：
 * 快速路径立即放行、低速阻塞 pacing、中断可退出、不限速直接放行。
 */
@DisplayName("UserTransferLimiter 双桶复用 TokenBucket 行为不变")
class UserTransferLimiterTest {

    @Test
    @DisplayName("下载：高速率下立即放行")
    void downloadFastPathReturnsImmediately() {
        UserTransferLimiter limiter = new UserTransferLimiter();
        long start = System.nanoTime();
        limiter.acquireDownload(1L, 1000L, 1_000_000L);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 100L,
                "令牌充足时应立即返回");
    }

    @Test
    @DisplayName("下载：字节数超过桶深时按速率阻塞 pacing")
    void downloadBlocksWhenTokensInsufficient() {
        UserTransferLimiter limiter = new UserTransferLimiter();
        long start = System.nanoTime();
        // 桶深 = max(8192, 10000) = 10000，申请 30000 需等待约 2 秒补充
        limiter.acquireDownload(2L, 30_000L, 10_000L);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsedMs >= 1500L, "令牌不足时应阻塞等待补充，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("上传 pacing：高速率下立即放行")
    void uploadPaceFastPathReturnsImmediately() {
        UserTransferLimiter limiter = new UserTransferLimiter();
        long start = System.nanoTime();
        limiter.acquireUploadPace(3L, 1000L, 1_000_000L);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 100L,
                "令牌充足时应立即返回");
    }

    @Test
    @DisplayName("阻塞等待期间线程中断可安全退出并保留中断标记")
    void acquireExitsOnInterrupt() throws InterruptedException {
        UserTransferLimiter limiter = new UserTransferLimiter();
        Thread t = new Thread(() -> limiter.acquireDownload(4L, 1_000_000L, 1_000L));
        t.start();
        Thread.sleep(200L);
        t.interrupt();
        t.join(5_000L);
        assertFalse(t.isAlive(), "中断后 acquire 应立即返回");
        assertTrue(t.isInterrupted(), "应恢复线程中断标记");
    }

    @Test
    @DisplayName("不限速（rate<=0）时直接放行不阻塞")
    void zeroRateSkipsLimiting() {
        UserTransferLimiter limiter = new UserTransferLimiter();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            limiter.acquireDownload(5L, 1_000_000L, 0L);
            limiter.acquireUploadPace(5L, 1_000_000L, -1L);
        });
    }

    @Test
    @DisplayName("上传门控：单分片在途窗口为 1，未确认前重复申请返回重试")
    void uploadGateWindowBlocksSecondChunk() {
        UserTransferLimiter limiter = new UserTransferLimiter();
        UserTransferLimiter.AcquireResult first = limiter.tryAcquireUpload(6L, 1024L, 1_000_000L);
        UserTransferLimiter.AcquireResult second = limiter.tryAcquireUpload(6L, 1024L, 1_000_000L);
        assertTrue(first.isAllowed(), "窗口空闲时应允许签发");
        assertFalse(second.isAllowed(), "在途未确认时应拒绝并返回重试");
        limiter.releaseUpload(6L);
        UserTransferLimiter.AcquireResult third = limiter.tryAcquireUpload(6L, 1024L, 1_000_000L);
        assertTrue(third.isAllowed(), "确认释放后应恢复允许");
    }
}
