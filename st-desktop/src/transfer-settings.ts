/**
 * 传输设置持有器 + 令牌桶限速器
 * 被 upload-manager / download-manager / task-scheduler 共享使用
 */
import { setMaxParallelTasks } from './task-scheduler';

export interface TransferSettings {
  maxParallelTasks: number;
  uploadSpeedLimit: number; // KB/s, 0 = unlimited
  downloadSpeedLimit: number; // KB/s, 0 = unlimited
}

const DEFAULT_SETTINGS: TransferSettings = {
  maxParallelTasks: 3,
  uploadSpeedLimit: 0,
  downloadSpeedLimit: 0,
};

let currentSettings: TransferSettings = { ...DEFAULT_SETTINGS };

// ==================== 令牌桶限速器 ====================

export class TokenBucketLimiter {
  private refillRate: number = 0; // bytes per second, 0 = unlimited
  private capacity: number = Infinity; // max burst
  private tokens: number = Infinity;
  private lastRefill: number = Date.now();

  setRate(bytesPerSecond: number): void {
    this.refillRate = bytesPerSecond;
    if (bytesPerSecond > 0) {
      // 容量至少允许一个 5MB 分片突发
      this.capacity = Math.max(bytesPerSecond, 5 * 1024 * 1024);
      this.tokens = Math.min(this.tokens, this.capacity);
    } else {
      this.capacity = Infinity;
      this.tokens = Infinity;
    }
  }

  /** 获取指定字节数的令牌，不足则阻塞等待 */
  async acquire(bytes: number): Promise<void> {
    if (this.refillRate <= 0) return;

    while (true) {
      this.refill();
      if (this.tokens >= bytes) {
        this.tokens -= bytes;
        return;
      }
      const deficit = bytes - this.tokens;
      const waitMs = Math.ceil((deficit / this.refillRate) * 1000);
      await new Promise((r) => setTimeout(r, Math.max(waitMs, 50)));
    }
  }

  /** 尝试获取令牌（非阻塞），返回是否成功 */
  tryAcquire(bytes: number): boolean {
    if (this.refillRate <= 0) return true;
    this.refill();
    if (this.tokens >= bytes) {
      this.tokens -= bytes;
      return true;
    }
    return false;
  }

  private refill(): void {
    const now = Date.now();
    const elapsed = (now - this.lastRefill) / 1000;
    this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.refillRate);
    this.lastRefill = now;
  }
}

// 共享限速器实例
export const uploadLimiter = new TokenBucketLimiter();
export const downloadLimiter = new TokenBucketLimiter();

// ==================== 设置管理 ====================

export function getTransferSettings(): TransferSettings {
  return { ...currentSettings };
}

export function setTransferSettings(settings: Partial<TransferSettings>): void {
  currentSettings = { ...currentSettings, ...settings };

  // 同步限速器（KB/s -> B/s）
  uploadLimiter.setRate(currentSettings.uploadSpeedLimit * 1024);
  downloadLimiter.setRate(currentSettings.downloadSpeedLimit * 1024);

  // 同步并行任务数
  if (settings.maxParallelTasks !== undefined) {
    setMaxParallelTasks(settings.maxParallelTasks);
  }
}

// 启动时用默认值初始化
setTransferSettings(DEFAULT_SETTINGS);
