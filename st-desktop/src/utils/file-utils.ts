import fs from 'fs';
import path from 'path';
import { app } from 'electron';

export const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
export const CONCURRENCY = 5;

/**
 * 读取文件指定分片
 */
export function readChunk(filePath: string, chunkIndex: number, chunkSize: number): Buffer {
  const offset = (chunkIndex - 1) * chunkSize;
  const fd = fs.openSync(filePath, 'r');
  try {
    const stat = fs.fstatSync(fd);
    const length = Math.min(chunkSize, stat.size - offset);
    const buf = Buffer.alloc(length);
    fs.readSync(fd, buf, 0, length, offset);
    return buf;
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * 计算文件分片总数
 */
export function getTotalChunks(fileSize: number, chunkSize: number = CHUNK_SIZE): number {
  return Math.ceil(fileSize / chunkSize);
}

/**
 * 获取下载临时文件路径
 */
export function getDownloadTempPath(taskId: string): string {
  const dir = path.join(app.getPath('userData'), 'downloads');
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  return path.join(dir, `${taskId}.part`);
}

/**
 * 获取下载临时文件已下载字节数
 */
export function getDownloadedBytes(taskId: string): number {
  const tempPath = getDownloadTempPath(taskId);
  try {
    const stat = fs.statSync(tempPath);
    return stat.size;
  } catch {
    return 0;
  }
}

/**
 * 删除下载临时文件
 */
export function deleteDownloadTempFile(taskId: string): void {
  const tempPath = getDownloadTempPath(taskId);
  try {
    fs.unlinkSync(tempPath);
  } catch {
    // ignore
  }
}

/**
 * 检查路径是否已存在，若存在则按 Windows 规则生成不重名的路径
 * 如 file.txt -> file (1).txt -> file (2).txt
 */
export function getUniqueFilePath(targetPath: string): string {
  if (!fs.existsSync(targetPath)) return targetPath;
  const dir = path.dirname(targetPath);
  const ext = path.extname(targetPath);
  const baseName = path.basename(targetPath, ext);
  let counter = 1;
  let candidate: string;
  do {
    candidate = path.join(dir, `${baseName} (${counter})${ext}`);
    counter++;
  } while (fs.existsSync(candidate));
  return candidate;
}

/**
 * 重命名临时文件为最终文件名（跨盘符时自动降级为复制+删除）
 */
export function finalizeDownloadFile(taskId: string, savePath: string): void {
  const tempPath = getDownloadTempPath(taskId);
  const dir = path.dirname(savePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  try {
    fs.renameSync(tempPath, savePath);
  } catch (err: any) {
    if (err.code === 'EXDEV') {
      // 跨设备（如 C: -> E:），rename 不支持，降级为复制后删除
      fs.copyFileSync(tempPath, savePath);
      fs.unlinkSync(tempPath);
    } else {
      throw err;
    }
  }
}
