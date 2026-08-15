/**
 * 同步引擎纯函数工具（不依赖 Electron/DB，便于单元测试）。
 *
 * 包含：冲突副本命名与识别、本地变更判定、冲突副本相对路径推导。
 * 背景（20260815-sync-refactor）：旧实现冲突名只有秒级时间戳，同秒两次冲突会互相覆盖；
 * 且冲突副本未登记 sync_state 被监听器当作新文件回流上传，形成死循环。
 */

/** 冲突副本命名标签（与云端/本地版本语义对应） */
export type ConflictTag = '本地' | '冲突';

/** 机器生成的冲突副本名：`xxx (本地-20260815141555).zip` 或 `xxx (冲突-20260815141555-1).zip` */
const CONFLICT_COPY_RE = /\((本地|冲突)-\d{14}(?:-\d+)?\)(?:\.[^/\\]+)?$/;

/** 判断文件名是否为引擎机器生成的冲突副本 */
export function isConflictCopyName(name: string): boolean {
  return CONFLICT_COPY_RE.test(name);
}

/**
 * 需要忽略的本地临时/系统文件（不应同步到云端）：
 * - Office 锁/临时文件：`~$xxx.xlsx`（OnlyOffice/Excel 打开文档时生成）
 * - 系统元数据：.DS_Store / Thumbs.db / .git / .Trash 等点开头文件
 * - 编辑器临时文件：*.tmp / *.swp / *.lock
 */
export function isIgnoredLocalPath(relPath: string): boolean {
  if (!relPath || relPath === '/') return false;
  const name = relPath.split('/').pop() || relPath;
  if (name.startsWith('~$')) return true;
  if (name === '.DS_Store' || name === 'Thumbs.db' || name === 'desktop.ini') return true;
  if (name.startsWith('.') && !name.startsWith('..')) return true;
  if (/\.(tmp|swp|lock)$/i.test(name)) return true;
  return false;
}

/**
 * 生成唯一的冲突副本路径。
 * @param filePath   原文件路径
 * @param tag        标签：本地 / 冲突
 * @param exists     判断路径是否已存在（用于避免同秒多次冲突互相覆盖）
 */
export function uniqueConflictName(
  filePath: string,
  tag: ConflictTag,
  exists: (p: string) => boolean,
): string {
  const base = conflictName(filePath, tag, 0);
  if (!exists(base)) return base;
  for (let i = 1; i < 1000; i++) {
    const candidate = conflictName(filePath, tag, i);
    if (!exists(candidate)) return candidate;
  }
  // 极端兜底：追加毫秒时间戳，保证唯一
  return conflictName(filePath, tag, Date.now() % 1000);
}

function conflictName(filePath: string, tag: ConflictTag, seq: number): string {
  const ext = filePath.includes('.') ? filePath.substring(filePath.lastIndexOf('.')) : '';
  const dotIdx = filePath.lastIndexOf('.');
  const baseName = dotIdx > 0 ? filePath.substring(0, dotIdx) : filePath;
  const ts = timestampStr();
  const suffix = seq > 0 ? `-${seq}` : '';
  return `${baseName} (${tag}-${ts}${suffix})${ext}`;
}

function timestampStr(date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

/**
 * 本地文件是否在“上次同步后”被修改。
 * 以 sync_state.local_mtime 为准：未记录（undefined）一律视为可能已修改。
 */
export function isLocallyChanged(
  state: { localMtime?: number } | null | undefined,
  mtimeMs: number,
): boolean {
  return !state || (state.localMtime ?? 0) < mtimeMs;
}

/**
 * 由相对路径推导冲突副本在 sync_state 中的相对路径。
 * @param relPath    原文件相对路径（以 / 开头）
 * @param conflictFile 冲突副本绝对路径
 * @param rootPath   同步根绝对路径
 */
export function conflictRelPath(relPath: string, conflictFile: string, rootPath: string): string {
  const rel = conflictFile.startsWith(rootPath)
    ? conflictFile.substring(rootPath.length).split(/[\\/]/).filter(Boolean).join('/')
    : relPath.replace(/\/[^/]+$/, '') + '/' + conflictFile.split(/[\\/]/).pop();
  return '/' + rel.replace(/^\/+/, '');
}
