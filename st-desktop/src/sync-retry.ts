/**
 * 同步上传失败退避工具（纯函数，便于单元测试）。
 * <p>
 * 背景：上传失败后若不做退避，任何触发（WS 通知 / 文件监听 / 30s 定时）都会对同一文件
 * 立即重试，形成重试风暴（曾因雪花 ID 精度 bug 实测每秒刷屏）。本模块负责：
 * - 按失败次数计算指数退避时长（30s -> 60s -> ... -> 1h 封顶）；
 * - 判定某个文件当前是否允许重试（用户再次修改文件时立即重试，不受退避限制）。
 */

/** 退避基数：首次失败后等待 30s */
export const RETRY_BASE_MS = 30_000;
/** 退避上限：1 小时，避免长时间无法同步也不给机会 */
export const RETRY_CAP_MS = 3_600_000;

/**
 * 计算第 failCount 次失败后的退避时长（ms）。
 * 公式：min(1h, 30s * 2^(failCount-1))；failCount<=0 时按首次失败处理。
 */
export function computeBackoffMs(failCount: number): number {
  const n = failCount > 0 ? failCount : 1;
  const ms = RETRY_BASE_MS * Math.pow(2, n - 1);
  return Math.min(RETRY_CAP_MS, Math.round(ms));
}

/** 同步状态中与重试相关的字段（从 sync_state 读取） */
export interface RetryState {
  failCount?: number;
  failMtime?: number;
  nextRetryAt?: number;
}

/**
 * 判定文件当前是否允许上传重试。
 * @param state          该文件的 sync_state（可空）
 * @param currentMtimeMs 当前本地文件 mtime（ms）
 * @param now            当前时间（epoch ms）
 * @returns true=允许上传；false=仍在退避期，跳过
 */
export function shouldRetryUpload(
  state: RetryState | null | undefined,
  currentMtimeMs: number,
  now: number,
): boolean {
  if (!state || state.failCount == null || state.failCount <= 0) {
    // 无失败记录或已清零：正常上传
    return true;
  }
  // 用户再次修改了文件（mtime 与失败时不同）：视为新操作，立即重试
  if (state.failMtime != null && currentMtimeMs !== state.failMtime) {
    return true;
  }
  // 未设置重试时间或已到期：允许重试
  if (state.nextRetryAt == null) {
    return true;
  }
  return now >= state.nextRetryAt;
}
