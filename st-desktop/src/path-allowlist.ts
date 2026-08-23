/**
 * 文件操作路径白名单（主进程）
 *
 * 目的：IPC 层对 shell / 上传 / 下载 / 同步等文件操作做路径校验，
 * 只允许「用户通过系统对话框选择」「系统下载目录」或「已注册同步根」的路径，
 * 防止渲染进程被 XSS 后调用 trashItem / openPath / startUpload 等读写任意本地文件。
 */
import { app } from 'electron';
import path from 'path';

/** 用户通过对话框 selectFiles 选中的文件路径（精确匹配） */
const allowedFiles = new Set<string>();
/** 用户通过对话框 selectFolder 选择的目录 / 系统下载目录（前缀匹配） */
const allowedDirs = new Set<string>();

const norm = (p: string): string => path.resolve(p);

/** 记录一个用户可操作的路径（文件或目录，统一加入后按前缀/精确判定） */
export function allowPath(fileOrDir: string): void {
  if (!fileOrDir) return;
  const r = norm(fileOrDir);
  allowedFiles.add(r);
  allowedDirs.add(r);
}

/** 允许系统下载目录内任意路径（多选下载、单文件下载默认保存目录） */
export function allowDownloadsDir(): void {
  try {
    allowedDirs.add(norm(app.getPath('downloads')));
  } catch {
    // userData 不可用时忽略
  }
}

/** 是否允许对给定路径执行文件操作 */
export function isAllowedPath(p: string): boolean {
  if (!p) return false;
  const r = norm(p);
  for (const dir of allowedDirs) {
    if (r === dir || r.startsWith(dir + path.sep)) return true;
  }
  return allowedFiles.has(r);
}
