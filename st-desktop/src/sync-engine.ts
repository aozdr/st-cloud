import fs from 'fs';
import os from 'os';
import path from 'path';
import { BrowserWindow } from 'electron';
import { apiClient } from './api-client';
import { startUpload } from './upload-manager';
import { getTask, insertSyncHistory, setBlockHashes, deleteBlockHashes } from './database';
import {
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  upsertSyncConfig, getSyncConfig, deleteSyncConfig,
  resetSyncData,
} from './database';
import { FileWatcher, type FileChangeEvent } from './file-watcher';
import { calculateSampledMd5, calculateFileMd5 } from './utils/md5';
import { computeBackoffMs, shouldRetryUpload } from './sync-retry';
import { calculateBlockHashes, readBlockData, BLOCK_SIZE } from './utils/block-hash';
import {
  uniqueConflictName,
  isLocallyChanged,
  conflictRelPath,
  isConflictCopyName,
  isIgnoredLocalPath,
} from './sync-utils';

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

/** 块级同步阈值：文件 >=8MB 且为更新已有文件时走块级增量上传 */
const BLOCK_SYNC_THRESHOLD = 8 * 1024 * 1024;

/**
 * 同步引擎版本：同步逻辑变更（含冲突/状态语义）时 +1。
 * 客户端本地版本与 sync_config.sync_version 不一致 → 触发一次全量重建（清本地库 + 云端快照对账）。
 * V3：sync_state 改为 (root_id, local_path) 复合主键，修复重新配置同步根后旧状态污染。
 */
const SYNC_ENGINE_VERSION = 3;

/** 引擎自写路径的 TTL（ms）：落盘后短期内监听事件即使到达也跳过，防止自激上传 */
const ENGINE_WRITE_TTL_MS = 30_000;

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
  /** 同步执行期间到达但未处理的事件（合并，不丢弃） */
  private pendingEvents: FileChangeEvent[] | null = null;
  /** 引擎自身写入的相对路径 -> 过期时间戳（自激过滤） */
  private engineWritten = new Map<string, number>();

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

    const config = getSyncConfig(this.root.rootId)
      ?? { rootId: this.root.rootId, localPath: this.root.localPath, cursor: 0, status: 'active' };
    // 版本门控：版本不一致或从未成功同步过 → 全量重建一次；否则只走增量
    const needFullSync = config.syncVersion !== SYNC_ENGINE_VERSION || config.lastSyncAt == null;
    const prevCursor = config.cursor ?? 0;
    if (needFullSync) {
      syncLog('info', '同步引擎版本变更，执行全量重建（清本地库 + 云端快照对账）');
      this.cleanupLocalJunkFiles();
      resetSyncData();
      const ok = await this.fullReconcile();
      // 全量对账必须完整成功才固化版本；失败保留旧版本，下次启动/定时器继续全量
      // 游标保留重建前位置：全量快照覆盖现状，增量从重建前游标继续，不重放整段历史日志
      upsertSyncConfig({
        rootId: this.root.rootId,
        localPath: this.root.localPath,
        cursor: prevCursor,
        status: 'active',
        syncVersion: ok ? SYNC_ENGINE_VERSION : undefined,
        lastSyncAt: ok ? Date.now() : undefined,
      });
      if (!ok) {
        syncLog('error', '全量对账未完整成功，保留旧版本标记，将在后续同步中重试');
      }
    }

    // 首次增量对账
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
    this.pendingEvents = null;
    this.engineWritten.clear();
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
    // 本地临时/系统文件（~$ Office 锁文件、.DS_Store、*.tmp 等）一律不参与同步
    if (isIgnoredLocalPath(relPath)) return true;
    if (this.exclusions.length === 0) return false;
    if (relPath === '/') return false;
    for (const excl of this.exclusions) {
      if (relPath === excl || relPath.startsWith(excl + '/')) return true;
    }
    return false;
  }

  /**
   * 将云端/本地相对路径安全解析到同步根内。
   * 相对路径含 '..' 或解析后越界时返回 null，调用方应跳过该条变更，
   * 防止恶意/异常路径导致删除或写入同步根之外的文件。
   */
  private absPathFor(relPath: string): string | null {
    if (!relPath) return null;
    const rootResolved = path.resolve(this.root.localPath);
    const abs = path.resolve(this.root.localPath, ...relPath.split('/').filter(Boolean));
    if (abs === rootResolved || abs.startsWith(rootResolved + path.sep)) return abs;
    syncLog('error', '拒绝越界路径: ' + relPath);
    return null;
  }

  // File件事件触发即时增量对账（带去重锁）
  private handleLocalEvents(events: FileChangeEvent[]): void {
    if (!this.running) return;
    this.syncOnce(events).catch((err) => {
      console.error('[sync] event-driven sync failed:', err);
    });
  }

  /** 记录引擎自身写入的路径（自激过滤：该路径在 TTL 内的监听事件不触发上传） */
  private markEngineWritten(relPath: string): void {
    this.engineWritten.set(relPath, Date.now() + ENGINE_WRITE_TTL_MS);
  }

  /** 是否应跳过该路径的本地事件（引擎自身刚写入，且状态 mtime 与落盘一致） */
  private isSelfWrite(relPath: string, mtimeMs: number): boolean {
    const expiry = this.engineWritten.get(relPath);
    if (expiry != null && expiry > Date.now()) {
      const state = getSyncState(this.root.rootId, relPath);
      // 状态 mtime 与当前一致 → 引擎写入；不一致 → 用户随后修改，放行
      return state != null && (state.localMtime ?? 0) >= mtimeMs;
    }
    return false;
  }

  /** 该路径是否在引擎写入 TTL 内（用于 unlink 事件：文件已不存在，无法比对 mtime） */
  private isEngineWrittenRecently(relPath: string): boolean {
    const expiry = this.engineWritten.get(relPath);
    return expiry != null && expiry > Date.now();
  }

  /**
   * 执行一次完整对账：本地变更上传 + 云端变更下载 + 删除对账 + 冲突处理
   * 游标采用 journal-id（sync_change_log.id），单调递增，无时钟漂移问题。
   * 游标仅在全部变更处理成功后才推进，保证断网恢复后不丢不重。
   */
  async syncOnce(localEvents?: FileChangeEvent[]): Promise<void> {
    if (this.syncing) {
      // 同步进行中：事件合并进 pending，本轮结束后自动续跑，绝不丢弃
      if (localEvents && localEvents.length > 0) {
        this.pendingEvents = this.pendingEvents
          ? [...this.pendingEvents, ...localEvents]
          : localEvents;
      }
      return;
    }
    this.syncing = true;
    try {
      // 取出合并的待处理事件（若有）
      if (this.pendingEvents && this.pendingEvents.length > 0 && (!localEvents || localEvents.length === 0)) {
        localEvents = this.pendingEvents;
        this.pendingEvents = null;
      }
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
      upsertSyncConfig({
        rootId: this.root.rootId,
        localPath: this.root.localPath,
        cursor: since,
        status: 'active',
        // 最后成功同步时间（epoch ms）：仅用于展示/审计，增量判定仍以 cursor 为准
        lastSyncAt: Date.now(),
        syncVersion: SYNC_ENGINE_VERSION,
      });
      emitSyncEvent('synced', { rootId: this.root.rootId, changes: allChanges.length, cursor: since });

      // 若有同步期间合并的事件，立即续跑一轮（防丢事件）
      if (this.pendingEvents && this.pendingEvents.length > 0) {
        const next = this.pendingEvents;
        this.pendingEvents = null;
        setImmediate(() => {
          this.syncOnce(next).catch((err) => console.error('[sync] pending events sync failed:', err));
        });
      }
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
      const state = getSyncState(this.root.rootId, '/' + rel);
      if (isLocallyChanged(state, stat.mtimeMs) && !this.isSelfWrite('/' + rel, stat.mtimeMs)) {
        // 失败退避：仍在退避期的文件跳过；用户再次修改（mtime 变化）不受退避限制
        if (state && !shouldRetryUpload(state, stat.mtimeMs, Date.now())) {
          const waitSec = state.nextRetryAt
            ? Math.max(0, Math.ceil((state.nextRetryAt - Date.now()) / 1000))
            : 0;
          syncLog('info', `跳过退避中: ${path.basename(localPath)}（${waitSec}s 后重试）`);
          continue;
        }
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
        // 引擎自身刚写过的路径被删除（如临时副本清理）不反向删云端
        if (this.isEngineWrittenRecently(rel)) continue;
        await this.handleLocalDelete(rel);
      } else if (evt.type === 'addDir') {
        syncLog('create', '创建文件夹: ' + evt.relativePath);
      } else if (evt.type === 'add' || evt.type === 'change') {
        if (!fs.existsSync(abs)) continue;
        const state = getSyncState(this.root.rootId, rel);
        const stat = fs.statSync(abs);
        if (isLocallyChanged(state, stat.mtimeMs) && !this.isSelfWrite(rel, stat.mtimeMs)) {
          // 失败退避：仍在退避期的文件跳过（事件驱动同样生效）
          if (state && !shouldRetryUpload(state, stat.mtimeMs, Date.now())) {
            const waitSec = state.nextRetryAt
              ? Math.max(0, Math.ceil((state.nextRetryAt - Date.now()) / 1000))
              : 0;
            syncLog('info', `跳过退避中: ${evt.relativePath}（${waitSec}s 后重试）`);
            continue;
          }
          await this.uploadFile(abs, rel, state?.nodeId);
        }
      }
    }
  }

  /**
   * 清理本地同步目录中的机器生成冲突副本（升级重建前调用，防止垃圾文件回流上传）。
   * 只匹配 `(本地-YYYYMMDDHHMMSS)` / `(冲突-YYYYMMDDHHMMSS)` 机器格式。
   */
  private cleanupLocalJunkFiles(): void {
    const allFiles = this.walkDir(this.root.localPath);
    for (const localPath of allFiles) {
      if (isConflictCopyName(path.basename(localPath))) {
        try {
          fs.unlinkSync(localPath);
          syncLog('delete', '清理冲突副本: ' + path.basename(localPath));
        } catch (err) {
          console.error('[sync] cleanup junk file failed:', localPath, err);
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
  /**
   * 块级增量上传：大文件（>=8MB）修改后仅上传变化块。
   * 流程：计算块哈希 -> block-check 对比 -> 上传缺失块 -> block-upload 组装。
   * 失败自动回退全量上传。
   */
  private async uploadFileBlockLevel(absPath: string, relPath: string, existingNodeId: string): Promise<boolean> {
    const stat = fs.statSync(absPath);
    const fileName = path.basename(absPath);

    try {
      // 1. 按 5MB 分块计算块哈希 + 全文件 MD5
      const blockHashes = await calculateBlockHashes(absPath);
      const fileMd5 = await calculateFileMd5(absPath);
      const totalBlocks = blockHashes.length;

      // 2. block-check：对比服务端块布局，初始化 multipart，返回可复用/缺失块
      syncLog('upload', `块检查: ${fileName} (${totalBlocks} 块)`);
      const checkRes = await apiClient.post('/sync/block-check', {
        // 雪花ID超出 JS 安全整数范围（2^53），必须按字符串传输，防止精度丢失查不到文件
        fileNodeId: existingNodeId,
        fileMd5,
        fileSize: stat.size,
        blockSize: BLOCK_SIZE,
        blocks: blockHashes.map((b) => ({ index: b.index, md5: b.md5, size: b.size })),
      });
      const checkData = checkRes.data?.data;
      if (!checkData) {
        throw new Error('block-check 响应异常: ' + (checkRes.data?.message || '未知错误'));
      }

      const reusableCount = checkData.reusableBlocks?.length ?? 0;
      const missingBlocks = checkData.missingBlocks ?? [];
      syncLog('upload', `块对比完成: ${fileName} 可复用 ${reusableCount} 块, 需上传 ${missingBlocks.length} 块`);

      // 3. 上传缺失块到预签名 URL
      for (const mb of missingBlocks) {
        const blockData = readBlockData(absPath, mb.blockIndex);
        let uploadError: Error | null = null;
        for (let attempt = 1; attempt <= 3; attempt++) {
          try {
            const response = await fetch(mb.presignedUrl, {
              method: 'PUT',
              body: blockData,
              headers: { 'Content-Type': 'application/octet-stream' },
            });
            if (!response.ok) {
              throw new Error(`块 ${mb.blockIndex} 上传失败: ${response.status}`);
            }
            uploadError = null;
            break;
          } catch (err) {
            uploadError = err as Error;
            if (attempt < 3) {
              await new Promise((r) => setTimeout(r, 1000 * Math.pow(2, attempt - 1)));
            }
          }
        }
        if (uploadError) throw uploadError;
      }

      // 4. block-upload：复制可复用块 + 合并 multipart + 更新元数据
      syncLog('upload', `块组装: ${fileName}`);
      const uploadRes = await apiClient.post('/sync/block-upload', {
        // 同上：雪花ID按字符串传输
        fileNodeId: existingNodeId,
        s3UploadId: checkData.s3UploadId,
        storagePath: checkData.storagePath,
        fileMd5,
        fileSize: stat.size,
        blockSize: BLOCK_SIZE,
        totalBlocks,
        blocks: blockHashes.map((b) => ({ index: b.index, md5: b.md5, size: b.size })),
      });
      const uploadData = uploadRes.data?.data;
      if (!uploadData) {
        throw new Error('block-upload 响应异常: ' + (uploadRes.data?.message || '未知错误'));
      }

      // 5. 缓存块哈希 + 更新同步状态
      setBlockHashes(this.root.rootId, relPath, blockHashes.map((b) => ({
        blockIndex: b.index, blockMd5: b.md5, blockSize: b.size,
      })));
      insertSyncHistory({ rootId: this.root.rootId, action: 'upload', fileName, relPath, status: 'success', detail: `块级上传(${reusableCount}块复用/${missingBlocks.length}块新增)` });
      upsertSyncState({ rootId: this.root.rootId,
        localPath: relPath,
        nodeId: existingNodeId,
        md5: fileMd5,
        size: stat.size,
        localMtime: stat.mtimeMs,
        status: 'synced',
        failCount: 0,
        failMtime: undefined,
        nextRetryAt: undefined,
      });
      syncLog('upload', `块级上传完成: ${fileName} (复用 ${reusableCount}/${totalBlocks} 块)`);
      return true;
    } catch (err) {
      syncLog('error', `块级上传失败: ${fileName} - ${String(err)}`);
      deleteBlockHashes(this.root.rootId, relPath);
      return false;
    }
  }

  private async uploadFile(absPath: string, relPath: string, existingNodeId?: string): Promise<void> {
    const stat = fs.statSync(absPath);
    const fileName = path.basename(absPath);
    // 更新已有文件且大小 >=8MB 时优先走块级增量上传，失败回退全量
    if (existingNodeId && stat.size >= BLOCK_SYNC_THRESHOLD) {
      const blockSuccess = await this.uploadFileBlockLevel(absPath, relPath, existingNodeId);
      if (blockSuccess) return;
    }

    const replaceFileId = existingNodeId || undefined;

    syncLog(replaceFileId ? 'upload' : 'create', replaceFileId ? '更新文件: ' + fileName : '上传新文件: ' + fileName);
    const taskId = await startUpload(absPath, this.root.cloudFolderNodeId, replaceFileId);

    const result = await this.waitForTask(taskId);

    if (result !== 'completed') {
      // 记录失败并进入指数退避，避免反复触发形成重试风暴；原实现静默失败（无日志、无退避）
      const failedTask = getTask(taskId);
      const errorMsg = failedTask?.error ? String(failedTask.error) : result;
      this.recordUploadFailure(relPath, stat.mtimeMs);
      emitSyncEvent('upload_failed', { relPath, error: errorMsg });
      const waitSec = Math.ceil(computeBackoffMs(getSyncState(this.root.rootId, relPath)?.failCount ?? 1) / 1000);
      syncLog('error', `上传失败: ${fileName} - ${errorMsg}（将在 ${waitSec}s 后重试）`);
      return;
    }

    const task = getTask(taskId);
    const nodeId = task?.fileId ? String(task.fileId) : existingNodeId;

    const md5 = await calculateSampledMd5(absPath, stat.size).catch(() => undefined);
    syncLog('upload', '上传完成: ' + fileName);
    insertSyncHistory({ rootId: this.root.rootId, action: 'upload', fileName, relPath, status: 'success' });
    upsertSyncState({ rootId: this.root.rootId,
      localPath: relPath,
      nodeId,
      md5,
      size: stat.size,
      localMtime: stat.mtimeMs,
      status: 'synced',
      failCount: 0,
      failMtime: undefined,
      nextRetryAt: undefined,
    });
  }

  /**
   * 上传失败记账：失败次数 +1、记录失败时的本地 mtime、按指数退避计算下次重试时间。
   * mtime 记录用于区分"同一文件反复失败"与"用户再次修改"（后者立即重试）。
   */
  private recordUploadFailure(relPath: string, mtimeMs: number): void {
    const prev = getSyncState(this.root.rootId, relPath);
    const failCount = (prev?.failCount ?? 0) + 1;
    upsertSyncState({ rootId: this.root.rootId,
      localPath: relPath,
      nodeId: prev?.nodeId,
      md5: prev?.md5,
      size: prev?.size,
      localMtime: prev?.localMtime,
      cloudMtime: prev?.cloudMtime,
      status: prev?.status ?? 'error',
      failCount,
      failMtime: mtimeMs,
      nextRetryAt: Date.now() + computeBackoffMs(failCount),
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
    const state = getSyncState(this.root.rootId, relPath);
    syncLog('delete', '删除文件: ' + path.basename(relPath));
    if (!state?.nodeId) {
      deleteSyncState(this.root.rootId, relPath);
      return;
    }
    try {
      await apiClient.post('/file/delete', { nodeIds: [String(state.nodeId)] });
      deleteSyncState(this.root.rootId, relPath);
      insertSyncHistory({ rootId: this.root.rootId, action: 'delete', fileName: path.basename(relPath), relPath, status: 'success' });
    } catch (err: any) {
      syncLog('error', '删除失败: ' + path.basename(relPath) + ' - ' + (err?.response?.data?.message || String(err)));
      insertSyncHistory({ rootId: this.root.rootId, action: 'delete', fileName: path.basename(relPath), relPath, status: 'error', detail: String(err) });
    }
  }

  /**
   * 全量对账：递归列举云端同步文件夹下所有文件，下载本地缺失的文件。
   * 作为增量 delta 的安全网，捕获 sync_change_log 缺失的历史文件（如同步事件功能部署前上传的文件）。
   */
  private async fullReconcile(): Promise<boolean> {
    syncLog('info', '开始全量对账...');
    try {
      const downloaded = await this.reconcileFolder(this.root.cloudFolderNodeId, '');
      if (downloaded > 0) {
        syncLog('info', `全量对账完成，下载了 ${downloaded} 个缺失文件`);
      } else {
        syncLog('info', '全量对账完成，无缺失文件');
      }
      return true;
    } catch (err) {
      syncLog('error', '全量对账失败: ' + String(err));
      return false;
    }
  }

  /**
   * 递归对账文件夹：列举云端子节点，下载本地缺失的文件，对子文件夹递归。
   * @param folderId 云端文件夹节点 ID
   * @param relPrefix 相对路径前缀（根为 "" ，子文件夹为 "/subfolder" ）
   * @returns 本次下载的文件数
   */
  private async reconcileFolder(folderId: string, relPrefix: string): Promise<number> {
    let downloaded = 0;
    let page = 1;
    let hasMore = true;
    while (hasMore) {
      const res = await withRetry(
        () => apiClient.get('/file/list', { params: { parentId: folderId, page, size: 100 } }),
        '全量对账列举文件',
      );
      const payload = res.data?.data ?? res.data;
      const records: Array<{
        id: string; parentId: string; nodeType: number; name: string;
        path: string; fileSize: number; fileMd5: string | null; updatedAt: string;
      }> = payload?.records ?? [];
      const totalPages: number = payload?.pages ?? 1;

      for (const node of records) {
        const relPath = relPrefix + '/' + node.name;
        if (this.isExcluded(relPath)) continue;
        const absPath = this.absPathFor(relPath);
        if (!absPath) continue;

        if (node.nodeType === 0) {
          // 文件夹：确保本地存在，递归对账
          if (!fs.existsSync(absPath)) {
            fs.mkdirSync(absPath, { recursive: true });
            syncLog('create', '创建文件夹(对账): ' + node.name);
          }
          const state = getSyncState(this.root.rootId, relPath);
          if (!state) {
            upsertSyncState({ rootId: this.root.rootId, localPath: relPath, nodeId: node.id, status: 'synced', cloudMtime: node.updatedAt });
          } else if (state.nodeId !== node.id) {
            // 目录状态存在但 node_id 与云端不一致（历史精度污染/漂移）：以云端为准刷新
            upsertSyncState({ rootId: this.root.rootId, localPath: relPath, nodeId: node.id, status: 'synced', cloudMtime: node.updatedAt });
          }
          downloaded += await this.reconcileFolder(node.id, relPath);
        } else {
          // 文件：本地不存在则下载；本地已存在则按内容比对决定“登记 / 冲突保留 / 刷新 node_id”
          const localExists = fs.existsSync(absPath);
          const state = getSyncState(this.root.rootId, relPath);
          if (!localExists) {
            const item: DeltaItem = {
              logId: 'reconcile',
              nodeId: node.id,
              parentId: node.parentId,
              changeType: 'CREATE',
              path: relPath,
              oldPath: null,
              name: node.name,
              nodeType: node.nodeType,
              size: node.fileSize ?? null,
              md5: node.fileMd5 ?? null,
              suffix: null,
              status: 0,
              updatedAt: node.updatedAt,
            };
            await this.downloadFile(item, absPath, relPath);
            downloaded++;
          } else {
            // 本地存在：云端 md5 与已登记 md5 不一致时才需要进一步处理
            const stat = fs.statSync(absPath);
            const cloudMd5 = node.fileMd5 ?? null;
            const stateMd5 = state?.md5 ?? null;
            if (cloudMd5 && stateMd5 !== cloudMd5) {
              // 同名且内容不一致：计算本地 md5（大小不同则直接判定不同，省一次全量哈希）
              let localMd5: string | null = null;
              if (stat.size === (node.fileSize ?? -1)) {
                localMd5 = await calculateFileMd5(absPath).catch(() => null);
              }
              if (localMd5 === cloudMd5) {
                // 内容实际一致：只登记状态（避免重复下载覆盖本地）
                upsertSyncState({ rootId: this.root.rootId,
                  localPath: relPath,
                  nodeId: node.id,
                  md5: cloudMd5,
                  size: stat.size,
                  localMtime: stat.mtimeMs,
                  cloudMtime: node.updatedAt,
                  status: 'synced',
                });
              } else {
                // 同名且内容不一致：按冲突流程保留两份，绝不静默覆盖本地修改
                const item: DeltaItem = {
                  logId: 'reconcile',
                  nodeId: node.id,
                  parentId: node.parentId,
                  changeType: 'UPDATE',
                  path: relPath,
                  oldPath: null,
                  name: node.name,
                  nodeType: node.nodeType,
                  size: node.fileSize ?? null,
                  md5: cloudMd5,
                  suffix: null,
                  status: 0,
                  updatedAt: node.updatedAt,
                };
                await this.handleConflict(absPath, relPath, item);
              }
            } else if (state && state.nodeId !== node.id) {
              // 本地与云端内容一致（md5 相同），但记录的 node_id 与云端不一致：
              // 以云端为准刷新 node_id/cloud_mtime 并清除失败退避，修复后立即恢复同步
              upsertSyncState({ rootId: this.root.rootId,
                localPath: relPath,
                nodeId: node.id,
                md5: state.md5 ?? node.fileMd5 ?? undefined,
                size: state.size ?? node.fileSize ?? undefined,
                localMtime: state.localMtime,
                cloudMtime: node.updatedAt,
                status: 'synced',
                failCount: 0,
                failMtime: undefined,
                nextRetryAt: undefined,
              });
            }
          }
        }
      }
      hasMore = page < totalPages;
      page++;
    }
    return downloaded;
  }

  /**
   * 处理云端变更：基于 changeType 分发
   * CREATE/UPDATE -> 下载；DELETE -> 删本地；MOVE/RENAME -> 移动/重命名本地
   */
  private async processCloudDelta(changes: DeltaItem[]): Promise<void> {
    for (const item of changes) {
      const relPath = item.path;
      const absPath = this.absPathFor(relPath);
      if (!absPath) continue;
      const state = getSyncState(this.root.rootId, relPath);

      // Skip excluded paths
      if (this.isExcluded(relPath)) continue;

      switch (item.changeType) {
        case 'DELETE': {
          // 云端已删除 -> 删本地（仅当本地未独立修改：mtime 与 md5 均未变）
          if (state && fs.existsSync(absPath)) {
            const stat = fs.statSync(absPath);
            const mtimeUnchanged = (state.localMtime ?? 0) >= stat.mtimeMs;
            let contentUnchanged = true;
            if (mtimeUnchanged && state.md5) {
              const localMd5 = await calculateSampledMd5(absPath, stat.size).catch(() => null);
              contentUnchanged = localMd5 === state.md5 || localMd5 == null;
            }
            if (mtimeUnchanged && contentUnchanged) {
              if (item.nodeType === 0) {
                fs.rmSync(absPath, { recursive: true, force: true });
              } else {
                fs.unlinkSync(absPath);
              }
              syncLog('delete', '删除: ' + path.basename(relPath) + '（云端已删除）');
            }
          }
          deleteSyncState(this.root.rootId, relPath);
          break;
        }

        case 'MOVE':
        case 'RENAME': {
          // 云端移动/重命名 -> 本地同步移动/重命名
          if (!item.oldPath) break;
          const oldAbs = this.absPathFor(item.oldPath);
          if (!oldAbs) break;
          const oldState = getSyncState(this.root.rootId, item.oldPath);

          // 无意义变更：新旧路径一致（服务端同目录移动/同名重命名产生的脏日志）
          // 只刷新云端元数据，不删除/重建状态，避免 local_mtime 被清空导致反复上传
          if (item.oldPath === relPath) {
            if (oldState) {
              upsertSyncState({ rootId: this.root.rootId,
                localPath: relPath,
                nodeId: item.nodeId,
                md5: item.md5 ?? oldState.md5 ?? undefined,
                size: item.size ?? oldState.size ?? undefined,
                localMtime: oldState.localMtime,
                cloudMtime: item.updatedAt,
                status: oldState.status ?? 'synced',
              });
            }
            break;
          }

          if (fs.existsSync(oldAbs)) {
            // 本地文件在云端移动期间被修改：不执行移动，避免本地修改被带走（后续 UPDATE 走冲突流程）
            if (oldState) {
              const stat = fs.statSync(oldAbs);
              if (isLocallyChanged(oldState, stat.mtimeMs) && !this.isSelfWrite(item.oldPath, stat.mtimeMs)) {
                syncLog('conflict', '移动跳过（本地已修改）: ' + path.basename(item.oldPath));
                break;
              }
            }
            const dir = path.dirname(absPath);
            if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
            fs.renameSync(oldAbs, absPath);
            syncLog(item.changeType === 'MOVE' ? 'move' : 'rename',
              (item.changeType === 'MOVE' ? '移动: ' : '重命名: ') + path.basename(item.oldPath) + ' -> ' + path.basename(relPath));
            // 更新 sync_state：删除旧路径，新路径携带旧状态（保留 local_mtime/md5/size）
            deleteSyncState(this.root.rootId, item.oldPath);
            upsertSyncState({ rootId: this.root.rootId,
              localPath: relPath,
              nodeId: item.nodeId,
              md5: item.md5 ?? oldState?.md5 ?? undefined,
              size: item.size ?? oldState?.size ?? undefined,
              localMtime: oldState?.localMtime,
              cloudMtime: item.updatedAt,
              status: 'synced',
            });
            // 文件夹移动：按前缀迁移子孙 sync_state，防止子树被当作新文件重传
            if (item.nodeType === 0) {
              this.migrateSyncStatePrefix(item.oldPath, relPath);
            }
          } else {
            // 旧文件不存在（可能本地也未同步过），按下载处理
            await this.downloadFile(item, absPath, relPath);
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
            upsertSyncState({ rootId: this.root.rootId, localPath: relPath, nodeId: item.nodeId, status: 'synced', cloudMtime: item.updatedAt });
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

  /**
   * 文件夹移动/重命名后按路径前缀迁移全部子孙 sync_state，
   * 保留 node_id/md5/size/local_mtime 等字段，避免子树被当作“本地新建”重传。
   */
  private migrateSyncStatePrefix(oldRel: string, newRel: string): void {
    const prefix = oldRel.endsWith('/') ? oldRel : oldRel + '/';
    const rows = getAllSyncStates(this.root.rootId).filter((s) => s.localPath.startsWith(prefix));
    for (const row of rows) {
      const suffix = row.localPath.substring(prefix.length - 1);
      const newPath = newRel + suffix;
      upsertSyncState({ ...row, localPath: newPath });
      deleteSyncState(this.root.rootId, row.localPath);
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
      this.markEngineWritten(relPath);
      syncLog('download', '下载完成: ' + path.basename(relPath));
      insertSyncHistory({ rootId: this.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'success' });
      upsertSyncState({ rootId: this.root.rootId,
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
      const state = getSyncState(this.root.rootId, relPath);
      await this.uploadFile(absPath, relPath, state?.nodeId);
      return;
    }

    if (strategy === 'latest_wins') {
      // 对比本地修改时间与云端更新时间，保留较新版本
      const localStat = fs.statSync(absPath);
      const localMtime = localStat.mtimeMs;
      const cloudTime = new Date(item.updatedAt).getTime();
      if (localMtime >= cloudTime) {
        const state = getSyncState(this.root.rootId, relPath);
        await this.uploadFile(absPath, relPath, state?.nodeId);
      } else {
        await this.downloadFile(item, absPath, relPath);
      }
      return;
    }

    // keep_both（默认）：保留两份副本
    // 1) 云端版下载为本地 "xxx (冲突-ts).ext"，并立即登记 sync_state，
    //    防止监听器把它当“本地新建”回流上传（旧实现死循环根因之一）。
    const conflictLocal = uniqueConflictName(absPath, '冲突', (p) => fs.existsSync(p));
    let cloudCopyOk = false;
    try {
      const res = await apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' });
      const ws = fs.createWriteStream(conflictLocal);
      await new Promise<void>((resolve, reject) => {
        res.data.pipe(ws);
        ws.on('finish', resolve);
        ws.on('error', reject);
      });
      cloudCopyOk = true;
      const cStat = fs.statSync(conflictLocal);
      const cRel = conflictRelPath(relPath, conflictLocal, this.root.localPath);
      this.markEngineWritten(cRel);
      upsertSyncState({ rootId: this.root.rootId,
        localPath: cRel,
        nodeId: item.nodeId,
        md5: item.md5 ?? undefined,
        size: cStat.size,
        localMtime: cStat.mtimeMs,
        cloudMtime: item.updatedAt,
        status: 'conflict',
      });
      insertSyncHistory({ rootId: this.root.rootId, action: 'download', fileName: path.basename(conflictLocal), relPath: cRel, status: 'success', detail: '冲突副本(云端版)' });
    } catch (err) {
      console.error('[sync] conflict download failed:', err);
    }

    // 2) 本地版上传为云端 "xxx (本地-ts).ext"：临时文件放系统临时目录，
    //    不再在同步目录内创建/删除临时文件（旧实现触发 unlink -> 反向删云端 的循环）。
    const conflictCloudName = uniqueConflictName(path.basename(absPath), '本地', () => false);
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'st-sync-conflict-'));
    let localCopyOk = false;
    try {
      const tempPath = path.join(tmpDir, conflictCloudName);
      fs.copyFileSync(absPath, tempPath);
      await this.uploadFile(tempPath, '/' + path.basename(tempPath));
      localCopyOk = true;
    } catch (err) {
      console.error('[sync] conflict local copy upload failed:', err);
    } finally {
      try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ignore */ }
    }

    // 3) 原文件状态：保留本地内容与 mtime（合并写入不擦 local_mtime），
    //    云端节点与 md5 记录为云端版本，避免下一轮扫描再次判定“本地已修改”反复上传。
    upsertSyncState({ rootId: this.root.rootId, localPath: relPath, nodeId: item.nodeId, md5: item.md5 ?? undefined, status: 'conflict' });
    insertSyncHistory({ rootId: this.root.rootId, action: 'conflict', fileName: path.basename(relPath), relPath, status: 'success', detail: strategy + (cloudCopyOk ? '' : '(云端副本下载失败)') + (localCopyOk ? '' : '(本地副本上传失败)') });
    emitSyncEvent('conflict', { relPath, cloudCopy: path.basename(conflictLocal) });
  }
}
