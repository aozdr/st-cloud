import crypto from 'crypto';
import fs from 'fs';

/**
 * 块级增量同步：块哈希计算工具（迭代 5）
 * 按 5MB 固定块大小分块计算 MD5，与后端块布局对齐。
 */

export const BLOCK_SIZE = 5 * 1024 * 1024; // 5MB（与 S3 multipart 最小块约束一致）

export interface BlockHash {
  index: number;
  md5: string;
  size: number;
}

/**
 * 计算文件的分块哈希列表
 * @param filePath 文件路径
 * @param blockSize 块大小（默认 5MB）
 * @returns 分块哈希列表（0-based 索引）
 */
export async function calculateBlockHashes(filePath: string, blockSize: number = BLOCK_SIZE): Promise<BlockHash[]> {
  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const totalBlocks = Math.ceil(fileSize / blockSize);
  const blocks: BlockHash[] = [];

  const fd = fs.openSync(filePath, 'r');
  try {
    for (let i = 0; i < totalBlocks; i++) {
      const offset = i * blockSize;
      const length = Math.min(blockSize, fileSize - offset);
      const buf = Buffer.alloc(length);
      fs.readSync(fd, buf, 0, length, offset);
      const hash = crypto.createHash('md5').update(buf).digest('hex');
      blocks.push({ index: i, md5: hash, size: length });
    }
  } finally {
    fs.closeSync(fd);
  }

  return blocks;
}

/**
 * 读取指定块的数据（用于上传缺失块）
 * @param filePath 文件路径
 * @param blockIndex 块序号（0-based）
 * @param blockSize 块大小
 * @returns 块数据 Buffer
 */
export function readBlockData(filePath: string, blockIndex: number, blockSize: number = BLOCK_SIZE): Buffer {
  const stat = fs.statSync(filePath);
  const offset = blockIndex * blockSize;
  const length = Math.min(blockSize, stat.size - offset);
  const buf = Buffer.alloc(length);
  const fd = fs.openSync(filePath, 'r');
  try {
    fs.readSync(fd, buf, 0, length, offset);
    return buf;
  } finally {
    fs.closeSync(fd);
  }
}
