import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { calculateFileMd5 as calculateWebMd5 } from './file-md5.ts';
import { calculateFileMd5 as calculateDesktopMd5 } from '../../../st-desktop/src/utils/md5.ts';

const MB = 1024 * 1024;

async function fixture(size, differentTail = false) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'st-hash-contract-'));
  const filePath = path.join(dir, 'fixture.bin');
  const fd = fs.openSync(filePath, 'w');
  const expected = crypto.createHash('md5');
  const parts = [];
  try {
    for (let offset = 0; offset < size; offset += MB) {
      const length = Math.min(MB, size - offset);
      const value = differentTail && offset >= 2 * MB ? 0x9a : (offset / MB) % 251;
      const bytes = Buffer.alloc(length, value);
      fs.writeSync(fd, bytes);
      expected.update(bytes);
      parts.push(bytes);
    }
  } finally {
    fs.closeSync(fd);
  }
  return { dir, filePath, blob: new Blob(parts), expected: expected.digest('hex') };
}

for (const size of [0, 1024, 5 * MB, 11 * MB, 100 * MB + 1]) {
  test(`Web/Desktop/Node 完整 MD5 一致：${size} bytes`, { timeout: 120_000 }, async () => {
    const data = await fixture(size);
    try {
      assert.equal(await calculateWebMd5(data.blob), data.expected);
      assert.equal(await calculateDesktopMd5(data.filePath), data.expected);
    } finally {
      fs.rmSync(data.dir, { recursive: true, force: true });
    }
  });
}

test('相同前 2MB 和大小但后续内容不同，不得产生相同完整 MD5', async () => {
  const first = await fixture(11 * MB);
  const second = await fixture(11 * MB, true);
  try {
    assert.equal(first.blob.size, second.blob.size);
    assert.deepEqual(await first.blob.slice(0, 2 * MB).arrayBuffer(),
      await second.blob.slice(0, 2 * MB).arrayBuffer());
    assert.notEqual(first.expected, second.expected);
    for (const data of [first, second]) {
      assert.equal(await calculateWebMd5(data.blob), data.expected);
      assert.equal(await calculateDesktopMd5(data.filePath), data.expected);
    }
  } finally {
    fs.rmSync(first.dir, { recursive: true, force: true });
    fs.rmSync(second.dir, { recursive: true, force: true });
  }
});
