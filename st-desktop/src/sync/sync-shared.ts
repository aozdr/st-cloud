import { BrowserWindow } from 'electron';

/**
 * 同步引擎共享模块：类型定义、常量、日志/事件、重试工具与引擎上下文接口。
 * 拆分自 sync-engine.ts，按「上传 / 下载 / 全量对账 / 主调度」四个模块协作。
 */

export interface SyncRootInfo {
  rootId: string;
  cloudFolderNodeId: string;
  localPath: string;
  conflictStrategy?: string;
}

export interface DeltaItem {
  logId: string;
  nodeId: string;
  parentId: string;
  changeType: 'CREATE' | 'UPDATE' | 'MOVE' | 'RENAME' | 'DELETE';
  path: string;
  oldPath: string | null;
  name: string;
  nodeType: number;
  size: number | null;
  md5: string | null;
  suffix: string | null;
  status: number;
  updatedAt: string;
}

export interface DeltaResponse {
  cursor: number;
  hasMore: boolean;
  changes: DeltaItem[];
}

/** 块级同步阈值：文件 >=8MB 且为更新已有文件时走块级增量上传 */
export const BLOCK_SYNC_THRESHOLD = 8 * 1024 * 1024;

/**
 * 同步引擎版本：同步逻辑变更（含冲突/状态语义）时 +1。
 * 客户端本地版本与 sync_config.sync_version 不一致 → 触发一次全量重建（清本地库 + 云端快照对账）。
 * V3：sync_state 改为 (root_id, local_path) 复合主键，修复重新配置同步根后旧状态污染。
 */
export const SYNC_ENGINE_VERSION = 3;

/** 引擎自写路径的 TTL（ms）：落盘后短期内监听事件即使到达也跳过，防止自激上传 */
export const ENGINE_WRITE_TTL_MS = 30_000;

export function emitSyncEvent(event: string, data: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) {
    win.webContents.send('sync:event', { event, data });
  }
}

export type LogType = 'upload' | 'download' | 'delete' | 'conflict' | 'info' | 'error' | 'move' | 'copy' | 'rename' | 'create';

export function syncLog(type: LogType, message: string, detail?: string): void {
  emitSyncEvent('log', { type, message, detail, time: new Date().toISOString() });
}

/**
 * 指数退避重试封装
 * 对网络操作（delta 拉取、下载）进行重试，最大重试 3 次，退避间隔 1s/2s/4s
 */
export async function withRetry<T>(fn: () => Promise<T>, label: string, maxRetries = 3): Promise<T> {
  let lastErr: unknown;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err: any) {
      lastErr = err;
      if (attempt < maxRetries) {
        const delay = Math.pow(2, attempt) * 1000;
        syncLog('error', `${label}失败，${delay / 1000}s 后重试 (${attempt + 1}/${maxRetries})`);
        await new Promise((r) => setTimeout(r, delay));
      }
    }
  }
  throw lastErr;
}

/**
 * 引擎上下文：各职责模块（上传/下载/对账）访问引擎状态与彼此能力的统一入口。
 * SyncEngine 主类实现该接口；uploadFile/downloadFile/handleConflict 由主类转发到对应模块实现，
 * 从而解耦模块间循环依赖。
 */
export interface SyncEngineCtx {
  readonly root: SyncRootInfo;
  readonly conflictStrategy: string;
  isExcluded(relPath: string): boolean;
  absPathFor(relPath: string): string | null;
  markEngineWritten(relPath: string): void;
  uploadFile(absPath: string, relPath: string, existingNodeId?: string): Promise<void>;
  downloadFile(item: DeltaItem, absPath: string, relPath: string): Promise<void>;
  handleConflict(absPath: string, relPath: string, item: DeltaItem): Promise<void>;
}
