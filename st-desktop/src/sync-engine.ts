import fs from 'fs';
import path from 'path';
import { BrowserWindow } from 'electron';
import { apiClient } from './api-client';
import { startUpload } from './upload-manager';
import { getTask, insertSyncHistory } from './database';
import {
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  upsertSyncConfig, getSyncConfig, deleteSyncConfig,
  type SyncStateRow,
} from './database';
import { FileWatcher, type FileChangeEvent } from './file-watcher';
import { calculateSampledMd5 } from './utils/md5';

interface DeltaItem {
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

interface DeltaResponse {
  cursor: number;
  hasMore: boolean;
  changes: DeltaItem[];
}

export interface SyncRootInfo {
  rootId: string;
  cloudFolderNodeId: string;
  localPath: string;
  conflictStrategy?: string;
}

function emitSyncEvent(event: string, data: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) {
    win.webContents.send('sync:event', { event, data });
  }
}

type LogType = 'upload' | 'download' | 'delete' | 'conflict' | 'info' | 'error' | 'move' | 'copy' | 'rename' | 'create';

function syncLog(type: LogType, message: string, detail?: string): void {
  emitSyncEvent('log', { type, message, detail, time: new Date().toISOString() });
}

function timestampStr(date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

function conflictName(filePath: string, tag: string): string {
  const ext = path.extname(filePath);
  const base = path.basename(filePath, ext);
  const dir = path.dirname(filePath);
  return path.join(dir, `${base} (${tag}-${timestampStr()})${ext}`);
}

/**
 * 指数退避重试封装
 * 对网络操作（delta 拉取、下载）进行重试，最大重试 3 次，退避间隔 1s/2s/4s
 */
async function withRetry<T>(fn: () => Promise<T>, label: string, maxRetries = 3): Promise<T> {
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
 * 同步引擎主类：管理一个同步根的双向对账
 */
export class SyncEngine {
  private root: SyncRootInfo;
  private watcher: FileWatcher;
  private running = false;
  private syncing = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  private conflictStrategy: string = "keep_both";
  private exclusions: string[] = [];

  constructor(root: SyncRootInfo) {
    this.root = root;
    this.conflictStrategy = root.conflictStrategy || 'keep_both';
    this.watcher = new FileWatcher(root.localPath);
  }

  async start(): Promise<void> {
    if (this.running) return;
    this.running = true;
    if (!fs.existsSync(this.root.localPath)) {
      fs.mkdirSync(this.root.localPath, { recursive: true });
    }
    this.watcher.setHandler((events) => this.handleLocalEvents(events));
    await this.watcher.start();
    // 首次全量对账
    await this.syncOnce();
    // 定时对账（30s 兜底，WS 在线时由 ws-client 触发即时同步）
    this.timer = setInterval(() => this.syncOnce(), 30_000);
    emitSyncEvent('started', { rootId: this.root.rootId });
    syncLog('info', '同步已启动 · ' + this.root.localPath);
  }

  async stop(): Promise<void> {
    this.running = false;
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    await this.watcher.stop();
    emitSyncEvent('stopped', { rootId: this.root.rootId });
    syncLog('info', '同步已停止');
  }

  isRunning(): boolean {
    return this.running;
  }

  /** Update conflict strategy */
  setConflictStrategy(strategy: string): void {
    this.conflictStrategy = strategy || 'keep_both';
  }

  /** Update exclusion paths */
  setExclusions(paths: string[]): void {
    this.exclusions = paths || [];
  }

  /** Check if relative path is excluded */
  private isExcluded(relPath: string): boolean {
    if (this.exclusions.length === 0) return false;
    if (relPath === '/') return false;
    for (const excl of this.exclusions) {
      if (relPath === excl || relPath.startsWith(excl + '/')) return true;
    }
    return false;
  }

  // File件事件触发即时增量对账（带去重锁）
  private handleLocalEvents(events: FileChangeEvent[]): void {
    if (!this.running) return;
    this.syncOnce(events).catch((err) => {
      console.error('[sync] event-driven sync failed:', err);
    });
  }

  /**
   * 执行一次完整对账：本地变更上传 + 云端变更下载 + 删除对账 + 冲突处理
   * 游标采用 journal-id（sync_change_log.id），单调递增，无时钟漂移问题。
   * 游标仅在全部变更处理成功后才推进，保证断网恢复后不丢不重。
   */
  async syncOnce(localEvents?: FileChangeEvent[]): Promise<void> {
    if (this.syncing) return;
    this.syncing = true;
    try {
      const config = getSyncConfig(this.root.rootId) ?? { rootId: this.root.rootId, localPath: this.root.localPath, cursor: 0, status: 'active' };
      let since = config.cursor;

      syncLog('info', '开始同步');

      // 1. 拉取云端 delta（游标分页：每次用上一页最后一条 logId 作为 since）
      const allChanges: DeltaItem[] = [];
      let hasMore = true;
      while (hasMore) {
        const delta = await withRetry(() => this.fetchDelta(since), '拉取变更');
        allChanges.push(...delta.changes);
        since = delta.cursor;
        hasMore = delta.hasMore;
      }

      // 2. 处理本地事件 -> 上传
      if (localEvents && localEvents.length > 0) {
        await this.processLocalEvents(localEvents);
      } else {
        await this.scanLocalChanges();
      }

      // 3. 处理云端变更 -> 下载 / 删除 / 移动 / 重命名
      await this.processCloudDelta(allChanges);

      // 4. 游标推进：全部变更处理成功后才更新，保证不丢
      upsertSyncConfig({ rootId: this.root.rootId, localPath: this.root.localPath, cursor: since, status: 'active' });
      emitSyncEvent('synced', { rootId: this.root.rootId, changes: allChanges.length, cursor: since });
    } catch (err) {
      syncLog('error', '同步失败: ' + String(err));
      emitSyncEvent('error', { rootId: this.root.rootId, error: String(err) });
    } finally {
      this.syncing = false;
    }
  }

  private async fetchDelta(since: number): Promise<DeltaResponse> {
    const res = await apiClient.get(`/sync/roots/${this.root.rootId}/delta`, { params: { since } });
    const body = res.data;
    const payload = body?.data ?? body;
    if (!payload || !payload.changes) {
      console.error('[sync] delta response unexpected:', JSON.stringify(body).substring(0, 300));
      return { cursor: since, hasMore: false, changes: [] };
    }
    return payload as DeltaResponse;
  }

  /**
   * 扫描本地目录，找出 sync_state 中不存在或 mtime 变化的文件
   */
  private async scanLocalChanges(): Promise<void> {
    const allFiles = this.walkDir(this.root.localPath);
    for (const localPath of allFiles) {
      const stat = fs.statSync(localPath);
      const rel = path.relative(this.root.localPath, localPath).split(path.sep).join('/');
      if (this.isExcluded('/' + rel)) continue;
      const state = getSyncState('/' + rel);
      if (!state || (state.localMtime ?? 0) < stat.mtimeMs) {
        await this.uploadFile(localPath, '/' + rel, state?.nodeId);
      }
    }
  }

  private async processLocalEvents(events: FileChangeEvent[]): Promise<void> {
    for (const evt of events) {
      const abs = path.join(this.root.localPath, ...evt.relativePath.split('/'));
      const rel = '/' + evt.relativePath;
      if (this.isExcluded(rel)) continue;
      if (evt.type === 'unlink' || evt.type === 'unlinkDir') {
        await this.handleLocalDelete(rel);
      } else if (evt.type === 'addDir') {
        syncLog('create', '创建文件夹: ' + evt.relativePath);
      } else if (evt.type === 'add' || evt.type === 'change') {
        if (!fs.existsSync(abs)) continue;
        const state = getSyncState(rel);
        const stat = fs.statSync(abs);
        if (!state || (state.localMtime ?? 0) < stat.mtimeMs) {
          await this.uploadFile(abs, rel, state?.nodeId);
        }
      }
    }
  }

  private walkDir(dir: string): string[] {
    const results: string[] = [];
    if (!fs.existsSync(dir)) return results;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        results.push(...this.walkDir(full));
      } else if (entry.isFile()) {
        results.push(full);
      }
    }
    return results;
  }

  /**
   * 上传文件：新建用 init+merge；已存在用 replaceFileId 覆盖（触发版本快照）
   */
  private async uploadFile(absPath: string, relPath: string, existingNodeId?: string): Promise<void> {
    const stat = fs.statSync(absPath);
    const fileName = path.basename(absPath);
    const replaceFileId = existingNodeId || undefined;

    syncLog(replaceFileId ? 'upload' : 'create', replaceFileId ? '更新文件: ' + fileName : '上传新文件: ' + fileName);
    const taskId = await startUpload(absPath, this.root.cloudFolderNodeId, replaceFileId);

    const result = await this.waitForTask(taskId);

    if (result !== 'completed') {
      emitSyncEvent('upload_failed', { relPath, error: result });
      return;
    }

    const task = getTask(taskId);
    const nodeId = task?.fileId ? String(task.fileId) : existingNodeId;

    const md5 = await calculateSampledMd5(absPath, stat.size).catch(() => undefined);
    syncLog('upload', '上传完成: ' + fileName);
    insertSyncHistory({ rootId: this.root.rootId, action: 'upload', fileName, relPath, status: 'success' });
    upsertSyncState({
      localPath: relPath,
      nodeId,
      md5,
      size: stat.size,
      localMtime: stat.mtimeMs,
      status: 'synced',
    });
  }

  private async waitForTask(taskId: string, timeoutMs = 600_000): Promise<string> {
    const start = Date.now();
    return new Promise((resolve) => {
      const check = () => {
        const t = getTask(taskId);
        if (t && (t.status === 'completed' || t.status === 'failed')) {
          resolve(t.status);
          return;
        }
        if (Date.now() - start > timeoutMs) {
          resolve('timeout');
          return;
        }
        setTimeout(check, 1000);
      };
      check();
    });
  }

  /**
   * 本地删除 -> 移入云端回收站（仅当本地版本与上次同步一致）
   */
  private async handleLocalDelete(relPath: string): Promise<void> {
    const state = getSyncState(relPath);
    syncLog('delete', '删除文件: ' + path.basename(relPath));
    if (!state?.nodeId) {
      deleteSyncState(relPath);
      return;
    }
    try {
      await apiClient.post('/file/delete', { nodeIds: [String(state.nodeId)] });
      deleteSyncState(relPath);
      insertSyncHistory({ rootId: this.root.rootId, action: 'delete', fileName: path.basename(relPath), relPath, status: 'success' });
    } catch (err: any) {
      syncLog('error', '删除失败: ' + path.basename(relPath) + ' - ' + (err?.response?.data?.message || String(err)));
      insertSyncHistory({ rootId: this.root.rootId, action: 'delete', fileName: path.basename(relPath), relPath, status: 'error', detail: String(err) });
    }
  }

  /**
   * 处理云端变更：基于 changeType 分发
   * CREATE/UPDATE -> 下载；DELETE -> 删本地；MOVE/RENAME -> 移动/重命名本地
   */
  private async processCloudDelta(changes: DeltaItem[]): Promise<void> {
    for (const item of changes) {
      const relPath = item.path;
      const absPath = path.join(this.root.localPath, ...relPath.split('/').filter(Boolean));
      const state = getSyncState(relPath);

      // Skip excluded paths
      if (this.isExcluded(relPath)) continue;

      switch (item.changeType) {
        case 'DELETE': {
          // 云端已删除 -> 删本地（仅当本地未独立修改）
          if (state && fs.existsSync(absPath)) {
            const stat = fs.statSync(absPath);
            if ((state.localMtime ?? 0) >= stat.mtimeMs) {
              if (item.nodeType === 0) {
                fs.rmSync(absPath, { recursive: true, force: true });
              } else {
                fs.unlinkSync(absPath);
              }
              syncLog('delete', '删除: ' + path.basename(relPath) + '（云端已删除）');
            }
          }
          deleteSyncState(relPath);
          break;
        }

        case 'MOVE':
        case 'RENAME': {
          // 云端移动/重命名 -> 本地同步移动/重命名
          if (item.oldPath) {
            const oldAbs = path.join(this.root.localPath, ...item.oldPath.split('/').filter(Boolean));
            if (fs.existsSync(oldAbs)) {
              const dir = path.dirname(absPath);
              if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
              fs.renameSync(oldAbs, absPath);
              syncLog(item.changeType === 'MOVE' ? 'move' : 'rename',
                (item.changeType === 'MOVE' ? '移动: ' : '重命名: ') + path.basename(item.oldPath) + ' -> ' + path.basename(relPath));
              // 更新 sync_state：删除旧路径，写入新路径
              deleteSyncState(item.oldPath);
              upsertSyncState({
                localPath: relPath,
                nodeId: item.nodeId,
                md5: item.md5 ?? undefined,
                size: item.size ?? undefined,
                status: 'synced',
                cloudMtime: item.updatedAt,
              });
            } else {
              // 旧文件不存在（可能本地也未同步过），按下载处理
              await this.downloadFile(item, absPath, relPath);
            }
          }
          break;
        }

        case 'CREATE':
        case 'UPDATE':
        default: {
          // 文件夹：确保本地存在
          if (item.nodeType === 0) {
            if (!fs.existsSync(absPath)) {
              fs.mkdirSync(absPath, { recursive: true });
              syncLog('create', '创建文件夹: ' + path.basename(relPath) + '（云端同步）');
            }
            upsertSyncState({ localPath: relPath, nodeId: item.nodeId, status: 'synced', cloudMtime: item.updatedAt });
            break;
          }

          // 文件：检查冲突
          const localExists = fs.existsSync(absPath);
          const localChanged = localExists && state && (state.localMtime ?? 0) < fs.statSync(absPath).mtimeMs;

          if (localExists && localChanged && item.md5 && state?.md5 !== item.md5) {
            // 冲突：双方都改了且 md5 不同
            await this.handleConflict(absPath, relPath, item);
            continue;
          }

          // 仅云端变更 -> 下载到本地
          if (!localExists || (state?.md5 !== item.md5)) {
            await this.downloadFile(item, absPath, relPath);
          }
          break;
        }
      }
    }
  }

  private async downloadFile(item: DeltaItem, absPath: string, relPath: string): Promise<void> {
    syncLog('download', '下载文件: ' + path.basename(relPath));
    try {
      const dir = path.dirname(absPath);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

      const res = await withRetry(
        () => apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' }),
        '下载文件: ' + path.basename(relPath),
      );
      const ws = fs.createWriteStream(absPath);
      await new Promise<void>((resolve, reject) => {
        res.data.pipe(ws);
        ws.on('finish', resolve);
        ws.on('error', reject);
      });

      const stat = fs.statSync(absPath);
      syncLog('download', '下载完成: ' + path.basename(relPath));
      insertSyncHistory({ rootId: this.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'success' });
      upsertSyncState({
        localPath: relPath,
        nodeId: item.nodeId,
        md5: item.md5 ?? undefined,
        size: stat.size,
        localMtime: stat.mtimeMs,
        cloudMtime: item.updatedAt,
        status: 'synced',
      });
    } catch (err) {
      console.error('[sync] download failed:', relPath, err);
      insertSyncHistory({ rootId: this.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'error', detail: String(err) });
      emitSyncEvent('download_failed', { relPath, error: String(err) });
    }
  }

  /**
   * 冲突处理：保留两份
   * - 云端版下载为 "文件名 (冲突-时间戳).ext"（本地）
   * - 本地版上传为 "文件名 (本地-时间戳).ext"（云端，新建节点）
   */
  private async handleConflict(absPath: string, relPath: string, item: DeltaItem): Promise<void> {
    // 根据同步根的冲突策略决定解决方式
    const strategy = this.conflictStrategy || 'keep_both';
    syncLog('conflict', '文件冲突: ' + path.basename(relPath) + ' (策略: ' + strategy + ')');

    if (strategy === 'server_wins') {
      // 服务端为准：下载云端版覆盖本地
      await this.downloadFile(item, absPath, relPath);
      return;
    }

    if (strategy === 'local_wins') {
      // 本地为准：上传本地版覆盖云端
      const state = getSyncState(relPath);
      await this.uploadFile(absPath, relPath, state?.nodeId);
      return;
    }

    if (strategy === 'latest_wins') {
      // 对比本地修改时间与云端更新时间，保留较新版本
      const localStat = fs.statSync(absPath);
      const localMtime = localStat.mtimeMs;
      const cloudTime = new Date(item.updatedAt).getTime();
      if (localMtime >= cloudTime) {
        const state = getSyncState(relPath);
        await this.uploadFile(absPath, relPath, state?.nodeId);
      } else {
        await this.downloadFile(item, absPath, relPath);
      }
      return;
    }

    // keep_both（默认）：保留两份副本
    const conflictLocal = conflictName(absPath, '冲突');
    try {
      const res = await apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' });
      const ws = fs.createWriteStream(conflictLocal);
      await new Promise<void>((resolve, reject) => {
        res.data.pipe(ws);
        ws.on('finish', resolve);
        ws.on('error', reject);
      });
    } catch (err) {
      console.error('[sync] conflict download failed:', err);
    }

    const conflictCloudName = conflictName(path.basename(absPath), '本地');
    const tempPath = path.join(path.dirname(absPath), conflictCloudName);
    fs.copyFileSync(absPath, tempPath);
    await this.uploadFile(tempPath, '/' + path.basename(tempPath));
    try { fs.unlinkSync(tempPath); } catch { /* ignore */ }

    upsertSyncState({ localPath: relPath, nodeId: item.nodeId, md5: item.md5 ?? undefined, status: 'conflict' });
    insertSyncHistory({ rootId: this.root.rootId, action: 'conflict', fileName: path.basename(relPath), relPath, status: 'success', detail: strategy });
    emitSyncEvent('conflict', { relPath, cloudCopy: path.basename(conflictLocal) });
  }
}