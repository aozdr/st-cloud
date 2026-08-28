import fs from 'fs';
import path from 'path';
import { apiClient } from './api-client';
import {
  insertSyncHistory,
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  upsertSyncConfig, getSyncConfig, deleteSyncConfig,
  resetSyncData,
} from './database';
import { FileWatcher, type FileChangeEvent } from './file-watcher';
import { calculateSampledMd5 } from './utils/md5';
import { shouldRetryUpload } from './sync-retry';
import {
  isLocallyChanged,
  isConflictCopyName,
  isIgnoredLocalPath,
} from './sync-utils';
import {
  syncLog, emitSyncEvent, withRetry,
  SYNC_ENGINE_VERSION, ENGINE_WRITE_TTL_MS,
  type SyncRootInfo, type DeltaItem, type DeltaResponse, type SyncEngineCtx,
} from './sync/sync-shared';
import { uploadFile as uploadFileImpl } from './sync/sync-upload';
import { downloadFile as downloadFileImpl, handleConflict as handleConflictImpl } from './sync/sync-download';
import { fullReconcile as fullReconcileImpl } from './sync/sync-reconcile';

// 向后兼容：既有模块从本文件导入 SyncRootInfo
export type { SyncRootInfo } from './sync/sync-shared';

/**
 * 同步引擎主类：管理一个同步根的双向对账。
 * 职责拆分（V2 结构，逻辑不变）：
 * - 本文件：生命周期 / 调度（syncOnce）/ 本地扫描与事件 / 云端 delta 分发 / 路径与自激过滤
 * - sync/sync-upload.ts：全量与块级增量上传、失败退避记账
 * - sync/sync-download.ts：云端变更下载、冲突处理
 * - sync/sync-reconcile.ts：全量对账（云端快照对账）
 * - sync/sync-shared.ts：共享类型 / 常量 / 日志 / 重试
 */
export class SyncEngine implements SyncEngineCtx {
  readonly root: SyncRootInfo;
  private watcher: FileWatcher;
  private running = false;
  private syncing = false;
  private timer: ReturnType<typeof setInterval> | null = null;
  conflictStrategy: string = "keep_both";
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
      const ok = await fullReconcileImpl(this);
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
  isExcluded(relPath: string): boolean {
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
  absPathFor(relPath: string): string | null {
    if (!relPath) return null;
    const rootResolved = path.resolve(this.root.localPath);
    const abs = path.resolve(this.root.localPath, ...relPath.split('/').filter(Boolean));
    if (abs === rootResolved || abs.startsWith(rootResolved + path.sep)) return abs;
    syncLog('error', '拒绝越界路径: ' + relPath);
    return null;
  }

  // 文件事件触发即时增量对账（带去重锁）
  private handleLocalEvents(events: FileChangeEvent[]): void {
    if (!this.running) return;
    this.syncOnce(events).catch((err) => {
      console.error('[sync] event-driven sync failed:', err);
    });
  }

  /** 记录引擎自身写入的路径（自激过滤：该路径在 TTL 内的监听事件不触发上传） */
  markEngineWritten(relPath: string): void {
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

  // ==================== 模块转发（SyncEngineCtx 实现） ====================
  // 上传/下载/对账/冲突实现拆分至 sync/ 子模块；主类转发以解耦模块间循环依赖。

  uploadFile(absPath: string, relPath: string, existingNodeId?: string): Promise<void> {
    return uploadFileImpl(this, absPath, relPath, existingNodeId);
  }

  downloadFile(item: DeltaItem, absPath: string, relPath: string): Promise<void> {
    return downloadFileImpl(this, item, absPath, relPath);
  }

  handleConflict(absPath: string, relPath: string, item: DeltaItem): Promise<void> {
    return handleConflictImpl(this, absPath, relPath, item);
  }
}
