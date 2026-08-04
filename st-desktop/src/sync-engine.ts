import fs from 'fs';
import path from 'path';
import { BrowserWindow } from 'electron';
import { apiClient } from './api-client';
import { startUpload } from './upload-manager';
import { getTask } from './database';
import {
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  upsertSyncConfig, getSyncConfig, deleteSyncConfig,
  type SyncStateRow,
} from './database';
import { FileWatcher, type FileChangeEvent } from './file-watcher';
import { calculateSampledMd5 } from './utils/md5';

interface DeltaItem {
  nodeId: string;
  parentId: string;
  path: string;
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
 * 同步引擎主类：管理一个同步根的双向对账
 */
export class SyncEngine {
  private root: SyncRootInfo;
  private watcher: FileWatcher;
  private running = false;
  private syncing = false;
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(root: SyncRootInfo) {
    this.root = root;
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
    // 定时对账（30s）
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

  // 文件事件触发即时增量对账（带去重锁）
  private handleLocalEvents(events: FileChangeEvent[]): void {
    if (!this.running) return;
    this.syncOnce(events).catch((err) => {
      console.error('[sync] event-driven sync failed:', err);
    });
  }

  /**
   * 执行一次完整对账：本地变更上传 + 云端变更下载 + 删除对账 + 冲突处理
   */
  async syncOnce(localEvents?: FileChangeEvent[]): Promise<void> {
    if (this.syncing) return;
    this.syncing = true;
    try {
      const config = getSyncConfig(this.root.rootId) ?? { rootId: this.root.rootId, localPath: this.root.localPath, cursor: 0, status: 'active' };
      const since = config.cursor;

      syncLog('info', '开始同步');
      // 1. 拉取云端 delta（分页拉取全部变更）
      const allChanges: DeltaItem[] = [];
      let serverCursor = since;
      let page = 0;
      let hasMore = true;
      while (hasMore) {
        const delta = await this.fetchDelta(since, page);
        allChanges.push(...delta.changes);
        serverCursor = delta.cursor;
        hasMore = delta.hasMore;
        if (hasMore) page++;
      }

            // 2. 处理本地事件 -> 上传
      if (localEvents && localEvents.length > 0) {
        await this.processLocalEvents(localEvents);
      } else {
        await this.scanLocalChanges();
      }

      // 3. 处理云端变更 -> 下载 / 删除
      await this.processCloudDelta(allChanges);

      // 4. 更新游标为服务端返回的 cursor
      upsertSyncConfig({ rootId: this.root.rootId, localPath: this.root.localPath, cursor: serverCursor, status: 'active' });
      emitSyncEvent('synced', { rootId: this.root.rootId, changes: allChanges.length, cursor: serverCursor });
    } catch (err) {
      syncLog('error', '同步失败: ' + String(err));
      emitSyncEvent('error', { rootId: this.root.rootId, error: String(err) });
    } finally {
      this.syncing = false;
    }
  }

  private async fetchDelta(since: number, page: number): Promise<DeltaResponse> {
    const res = await apiClient.get(`/sync/roots/${this.root.rootId}/delta`, { params: { since, page } });
    const body = res.data;
    // 后端返回 Result{code, message, data}
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

    // 复用 upload-manager（含 MD5/分片/断点续传/replaceFileId）
    syncLog(replaceFileId ? 'upload' : 'create', replaceFileId ? '更新文件: ' + fileName : '上传新文件: ' + fileName);
    const taskId = await startUpload(absPath, this.root.cloudFolderNodeId, replaceFileId);

    // 等待任务完成
    const result = await this.waitForTask(taskId);

    if (result !== 'completed') {
      emitSyncEvent('upload_failed', { relPath, error: result });
      return;
    }

    // 查询上传后的文件节点 ID
    const task = getTask(taskId);
    const nodeId = task?.fileId ? String(task.fileId) : existingNodeId;

    const md5 = await calculateSampledMd5(absPath, stat.size).catch(() => undefined);
    syncLog('upload', '上传完成: ' + fileName);
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
      // 未同步过，直接清理本地记录
      deleteSyncState(relPath);
      return;
    }
    try {
      const res = await apiClient.post('/file/delete', { nodeIds: [String(state.nodeId)] });
  
      deleteSyncState(relPath);
  
    } catch (err: any) {
      syncLog('error', '删除失败: ' + path.basename(relPath) + ' - ' + (err?.response?.data?.message || String(err)));
    }
  }

  /**
   * 处理云端变更：下载 / 删除本地 / 检测冲突
   */
  private async processCloudDelta(changes: DeltaItem[]): Promise<void> {
    for (const item of changes) {
      const relPath = item.path; // 相对路径，以 / 开头
      const absPath = path.join(this.root.localPath, ...relPath.split('/').filter(Boolean));
      const state = getSyncState(relPath);

      // 云端已回收站/删除
      if (item.status !== 0) {
        if (state && fs.existsSync(absPath)) {
          // 本地未修改才删（防误删本地新改动）
          const stat = fs.statSync(absPath);
          if ((state.localMtime ?? 0) >= stat.mtimeMs) {
            fs.unlinkSync(absPath);
            syncLog('delete', '删除文件: ' + path.basename(relPath) + '（云端已删除）');
          }
        }
        deleteSyncState(relPath);
        continue;
      }

      // 文件夹：确保本地存在
      if (item.nodeType === 0) {
        if (!fs.existsSync(absPath)) {
          fs.mkdirSync(absPath, { recursive: true });
          syncLog('create', '创建文件夹: ' + path.basename(relPath) + '（云端同步）');
        }
        upsertSyncState({ localPath: relPath, nodeId: item.nodeId, status: 'synced', cloudMtime: item.updatedAt });
        continue;
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
    }
  }

  private async downloadFile(item: DeltaItem, absPath: string, relPath: string): Promise<void> {
    syncLog('download', '下载文件: ' + path.basename(relPath));
    try {
      const dir = path.dirname(absPath);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

      const res = await apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' });
      const ws = fs.createWriteStream(absPath);
      await new Promise<void>((resolve, reject) => {
        res.data.pipe(ws);
        ws.on('finish', resolve);
        ws.on('error', reject);
      });

      const stat = fs.statSync(absPath);
      syncLog('download', '下载完成: ' + path.basename(relPath));
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
      emitSyncEvent('download_failed', { relPath, error: String(err) });
    }
  }

  /**
   * 冲突处理：保留两份
   * - 云端版下载为 "文件名 (冲突-时间戳).ext"（本地）
   * - 本地版上传为 "文件名 (本地-时间戳).ext"（云端，新建节点）
   */
  private async handleConflict(absPath: string, relPath: string, item: DeltaItem): Promise<void> {
    syncLog('conflict', '文件冲突: ' + path.basename(relPath) + '（已保留两份副本）');
    const conflictLocal = conflictName(absPath, '冲突');
    // 下载云端版到冲突副本
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

    // 上传本地版为新建节点（云端副本）
    const conflictCloudName = conflictName(path.basename(absPath), '本地');
    // 直接上传到同步根目录（新文件名）
    const tempPath = path.join(path.dirname(absPath), conflictCloudName);
    fs.copyFileSync(absPath, tempPath);
    await this.uploadFile(tempPath, '/' + path.basename(tempPath));
    // 清理临时文件（已上传）
    try { fs.unlinkSync(tempPath); } catch { /* ignore */ }

    upsertSyncState({ localPath: relPath, nodeId: item.nodeId, md5: item.md5 ?? undefined, status: 'conflict' });
    emitSyncEvent('conflict', { relPath, cloudCopy: path.basename(conflictLocal) });
  }
}
