import { test } from 'node:test';
import assert from 'node:assert/strict';
import { computeBackoffMs, shouldRetryUpload, RETRY_BASE_MS, RETRY_CAP_MS } from './sync-retry';

test('computeBackoffMs: 指数退避曲线 30s/60s/120s/240s', () => {
  assert.equal(computeBackoffMs(1), 30_000);
  assert.equal(computeBackoffMs(2), 60_000);
  assert.equal(computeBackoffMs(3), 120_000);
  assert.equal(computeBackoffMs(4), 240_000);
});

test('computeBackoffMs: 封顶 1 小时', () => {
  assert.equal(computeBackoffMs(8), RETRY_CAP_MS);
  assert.equal(computeBackoffMs(99), RETRY_CAP_MS);
});

test('computeBackoffMs: failCount<=0 按首次失败处理', () => {
  assert.equal(computeBackoffMs(0), RETRY_BASE_MS);
  assert.equal(computeBackoffMs(-3), RETRY_BASE_MS);
});

test('shouldRetryUpload: 无失败记录始终允许上传', () => {
  assert.equal(shouldRetryUpload(null, 100, 0), true);
  assert.equal(shouldRetryUpload(undefined, 100, 0), true);
  assert.equal(shouldRetryUpload({ failCount: 0 }, 100, 0), true);
});

test('shouldRetryUpload: 退避期内跳过', () => {
  const now = 1_000_000;
  const state = { failCount: 2, failMtime: 100, nextRetryAt: now + 60_000 };
  assert.equal(shouldRetryUpload(state, 100, now), false);
});

test('shouldRetryUpload: 退避到期允许重试', () => {
  const now = 1_000_000;
  const state = { failCount: 2, failMtime: 100, nextRetryAt: now };
  assert.equal(shouldRetryUpload(state, 100, now), true);
  assert.equal(shouldRetryUpload(state, 100, now + 1), true);
});

test('shouldRetryUpload: 用户再次修改（mtime 变化）立即重试', () => {
  const now = 1_000_000;
  const state = { failCount: 3, failMtime: 100, nextRetryAt: now + 3_600_000 };
  assert.equal(shouldRetryUpload(state, 999, now), true);
});

test('shouldRetryUpload: nextRetryAt 为空视为可重试', () => {
  assert.equal(shouldRetryUpload({ failCount: 1, failMtime: 100 }, 100, 0), true);
});
