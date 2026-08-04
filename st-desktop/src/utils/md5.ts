import crypto from 'crypto';
import fs from 'fs';

/**
 * 流式计算文件 MD5（避免全量加载到内存）
 */
export function calculateFileMd5(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('md5');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (data) => hash.update(data));
    stream.on('end', () => resolve(hash.digest('hex')));
    stream.on('error', reject);
  });
}

/**
 * 采样 MD5：大文件只取首尾和中间片段计算（与前端逻辑一致）
 * 适用于 >10MB 的文件
 */
export async function calculateSampledMd5(
  filePath: string,
  fileSize: number
): Promise<string> {
  if (fileSize <= 10 * 1024 * 1024) {
    return calculateFileMd5(filePath);
  }

  const CHUNK = 2 * 1024 * 1024; // 2MB 采样块
  const hash = crypto.createHash('md5');
  const fd = fs.openSync(filePath, 'r');

  try {
    // 首部
    const headBuf = Buffer.alloc(CHUNK);
    fs.readSync(fd, headBuf, 0, CHUNK, 0);
    hash.update(headBuf);

    // 中间
    const midBuf = Buffer.alloc(CHUNK);
    const midOffset = Math.floor(fileSize / 2) - Math.floor(CHUNK / 2);
    fs.readSync(fd, midBuf, 0, CHUNK, midOffset);
    hash.update(midBuf);

    // 尾部
    const tailBuf = Buffer.alloc(CHUNK);
    const tailOffset = Math.max(0, fileSize - CHUNK);
    fs.readSync(fd, tailBuf, 0, CHUNK, tailOffset);
    hash.update(tailBuf);

    return hash.digest('hex');
  } finally {
    fs.closeSync(fd);
  }
}
