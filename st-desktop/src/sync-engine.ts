import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { apiClient } from './api-client';
import {
  insertSyncHistory,
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  upsertSyncConfig, getSyncConfig, deleteSyncConfig,
} from './database';
import { FileWatcher, type FileChangeEvent } from './file-watcher';
import { calculateFileMd5 } from './utils/md5';
import { shouldRetryUpload } from './sync-retry';
import {
  isLocallyChanged,
  isIgnoredLocalPath,
} from './sync-utils';
import {
  syncLog, emitSyncEvent, withRetry,
  parseFileSize,
  SYNC_ENGINE_VERSION, ENGINE_WRITE_TTL_MS,
  type SyncRootInfo, type DeltaItem, type DeltaResponse, type SyncEngineCtx,
} from './sync/sync-shared';
import { uploadFile as uploadFileImpl } from './sync/sync-upload';
import { downloadFile as downloadFileImpl, handleConflict as handleConflictImpl } from './sync/sync-download';
import { fullReconcile as fullReconcileImpl, reconcileFolder } from './sync/sync-reconcile';
import { preserveAndRemove, resumeRecoveryIfPresent } from './sync/sync-recovery';

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

  constructor(root: SyncRootInfo, private readonly session?: {
    assertCurrent(): void;
    run<T>(action: () => T): T;
  }) {
    this.root = root;
    this.conflictStrategy = root.conflictStrategy || 'keep_both';
    this.watcher = new FileWatcher(root.localPath);
  }

  async start(): Promise<void> {
    this.session?.assertCurrent();
    if (this.running) return;
    this.running = true;
    if (!fs.existsSync(this.root.localPath)) {
      fs.mkdirSync(this.root.localPath, { recursive: true });
    }
    this.watcher.setHandler((events) => this.handleLocalEvents(events));

    const config = getSyncConfig(this.root.rootId)
      ?? { rootId: this.root.rootId, localPath: this.root.localPath, cursor: '0', status: 'active' };
    // 版本门控：版本不一致或从未成功同步过 → 全量重建一次；否则只走增量
    const needFullSync = config.syncVersion !== SYNC_ENGINE_VERSION || config.lastSyncAt == null;
    const prevCursor = config.cursor ?? '0';
    if (needFullSync) {
      syncLog('info', '同步引擎版本变更，保留原状态并执行云端快照对账');
      for (const localFile of this.walkDir(this.root.localPath)) {
        const relative = '/' + path.relative(this.root.localPath, localFile).split(path.sep).join('/');
        if (!this.isExcluded(relative) && !getSyncState(this.root.rootId, relative)) {
          upsertSyncState({ rootId: this.root.rootId, localPath: relative,
            localMtime: fs.statSync(localFile).mtimeMs, status: 'needs_review' });
        }
      }
      const ok = await fullReconcileImpl(this);
      this.session?.assertCurrent();
      // 全量对账必须完整成功才固化版本；失败抛出，由 manager 清理实例后允许重新启动。
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
        syncLog('error', '全量对账未完整成功，保留旧版本标记，请重试启动同步');
        this.running = false;
        throw new Error('全量对账未完整成功');
      }
    }

    await this.watcher.start();
    this.session?.assertCurrent();

    // 首次增量对账
    await this.syncOnce();
    this.session?.assertCurrent();
    // 定时对账（30s 兜底，WS 在线时由 ws-client 触发即时同步）
    this.timer = setInterval(() => {
      this.syncOnce().catch(err => console.error('[sync] periodic sync failed:', err));
    }, 30_000);
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
    // 云端只使用 / 分隔的相对路径；拒绝盘符、反斜杠和 ..，避免 Windows 路径被重新解释。
    if (relPath.includes('\\') || relPath.includes('\0') || relPath.includes(':')
        || relPath.split('/').includes('..')) {
      syncLog('error', '拒绝非法相对路径: ' + relPath);
      return null;
    }
    const rootResolved = path.resolve(this.root.localPath);
    const abs = path.resolve(this.root.localPath, ...relPath.split('/').filter(Boolean));
    if (abs === rootResolved || abs.startsWith(rootResolved + path.sep)) {
      // 字符串范围检查不能识别目录联接；逐层检查已存在的父路径，禁止跟随链接到同步根外。
      let current = rootResolved;
      for (const segment of path.relative(rootResolved, abs).split(path.sep).filter(Boolean)) {
        current = path.join(current, segment);
        try {
          if (fs.lstatSync(current).isSymbolicLink()) {
            syncLog('error', '拒绝同步目录中的符号链接: ' + relPath);
            return null;
          }
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code === 'ENOENT') break;
          throw error;
        }
      }
      return abs;
    }
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
    // WS/文件监听器可能在其它异步上下文调用，整轮仍绑定创建引擎时的会话。
    if (this.session) return this.session.run(() => this.syncOnceInSession(localEvents));
    return this.syncOnceInSession(localEvents);
  }

  private async syncOnceInSession(localEvents?: FileChangeEvent[]): Promise<void> {
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
      const config = getSyncConfig(this.root.rootId) ?? { rootId: this.root.rootId, localPath: this.root.localPath, cursor: '0', status: 'active' };
      let since = config.cursor;

      syncLog('info', '开始同步');

      // 每页完成事件和必要对账后才固化游标，失败时重放同一页。
      let changeCount = 0;
      let hasMore = true;
      while (hasMore) {
        const delta = await withRetry(() => this.fetchDelta(since), '拉取变更');
        this.session?.assertCurrent();
        if (delta.scopeProjectionVersion !== 2) throw new Error('同步服务端尚未支持范围投影 v2');
        if (delta.hasMore && BigInt(delta.cursor) <= BigInt(since)) throw new Error('同步游标没有前进');
        if (delta.reconcileRequired && !(await fullReconcileImpl(this))) throw new Error('同步根对账失败');
        await this.processCloudDelta(delta.changes);
        this.session?.assertCurrent();
        changeCount += delta.changes.length;
        since = delta.cursor;
        hasMore = delta.hasMore;
        upsertSyncConfig({ rootId: this.root.rootId, localPath: this.root.localPath,
          cursor: since, status: 'active', lastSyncAt: Date.now(), syncVersion: SYNC_ENGINE_VERSION });
      }

      // 云端移动先落地，随后只处理未冲突的本地事件。
      if (localEvents && localEvents.length > 0) {
        await this.processLocalEvents(localEvents);
      } else {
        await this.scanLocalChanges();
      }

      // 记录本轮成功时间。
      this.session?.assertCurrent();
      upsertSyncConfig({
        rootId: this.root.rootId,
        localPath: this.root.localPath,
        cursor: since,
        status: 'active',
        // 最后成功同步时间（epoch ms）：仅用于展示/审计，增量判定仍以 cursor 为准
        lastSyncAt: Date.now(),
        syncVersion: SYNC_ENGINE_VERSION,
      });
      emitSyncEvent('synced', { rootId: this.root.rootId, changes: changeCount, cursor: since });

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

  private async fetchDelta(since: string): Promise<DeltaResponse> {
    const res = await apiClient.get(`/sync/roots/${this.root.rootId}/delta`, { params: { since } });
    const body = res.data;
    const payload = body?.data ?? body;
    // 游标由服务端 Long 按字符串输出；数字在 JSON.parse 时可能已失去精度，不能再 String() 补救。
    if (!payload || !Array.isArray(payload.changes)
        || typeof payload.cursor !== 'string'
        || !/^(0|[1-9][0-9]*)$/.test(payload.cursor)
        || typeof payload.hasMore !== 'boolean') throw new Error('同步增量响应格式无效');
    return { ...payload, cursor: payload.cursor,
      changes: payload.changes.map((change: DeltaItem) => ({ ...change, size: parseFileSize(change.size) })) } as DeltaResponse;
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
      if (state && !state.nodeId) {
        syncLog('conflict', '本地旧状态缺少节点 ID，需人工核对: ' + rel);
        continue;
      }
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
        if (state && !state.nodeId) {
          syncLog('conflict', '本地旧状态缺少节点 ID，需人工核对: ' + rel);
          continue;
        }
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
    if (changes.length === 0) return;
    const rootNode = await this.readCloudNode(this.root.cloudFolderNodeId);
    if (!rootNode) throw new Error('同步根不可访问，停止应用增量');
    const cloudRootPath = rootNode.path;
    for (const loggedItem of changes) {
      let item = loggedItem;
      const relPath = item.path;
      const absPath = this.absPathFor(relPath);
      if (!absPath) throw new Error('增量事件路径越界');
      const state = getSyncState(this.root.rootId, relPath);

      // Skip excluded paths
      if (this.isExcluded(relPath)) continue;

      const current = await this.readCloudNode(item.nodeId);
      const currentRel = current?.status === 0 && (current.path === cloudRootPath
        || current.path.startsWith(cloudRootPath + '/'))
        ? '/' + current.path.slice(cloudRootPath.length).replace(/^\/+/, '') : null;
      if (item.changeType === 'DELETE' && currentRel === relPath) {
        // 旧删除日志重放时，同一节点已经回到该路径；不能删除当前有效内容。
        if (!(await fullReconcileImpl(this))) throw new Error('删除事件对账失败');
        continue;
      }
      if (item.changeType !== 'DELETE' && currentRel !== relPath) {
        // 日志快照已过时：先恢复当前云端树，再安全保留该节点的旧位置。
        if (currentRel && !(await fullReconcileImpl(this))) throw new Error('移动事件对账失败');
        for (const oldPath of [item.oldPath, item.path]) {
          if (!oldPath || oldPath === currentRel) continue;
          const oldState = getSyncState(this.root.rootId, oldPath);
          const oldAbs = this.absPathFor(oldPath);
          if (!oldAbs) throw new Error('历史事件路径越界');
          if (oldState?.nodeId === item.nodeId) {
            const preserve = fs.existsSync(oldAbs) ? preserveAndRemove : resumeRecoveryIfPresent;
            preserve(this.root.rootId, item.logId + '-' + crypto.createHash('sha256').update(oldPath).digest('hex').slice(0, 16),
              this.root.localPath, oldAbs, oldPath, (written) => this.markEngineWritten(written));
            for (const row of getAllSyncStates(this.root.rootId)) {
              if (row.localPath === oldPath || row.localPath.startsWith(oldPath + '/')) {
                deleteSyncState(this.root.rootId, row.localPath);
              }
            }
          }
        }
        continue;
      }

      // 下载接口返回当前内容，不能拿历史日志的大小/哈希校验当前版本。
      // 路径一致的内容事件采用刚读取的节点快照；读取后再次变化仍由下载校验拒绝，下一轮刷新快照重试。
      if (current && currentRel === relPath && item.nodeType === 1
          && (item.changeType === 'CREATE' || item.changeType === 'UPDATE')) {
        item = { ...item, size: current.size, md5: current.md5, updatedAt: current.updatedAt };
      }

      switch (item.changeType) {
        case 'DELETE': {
          if (relPath === '/') throw new Error('拒绝删除同步根');
          // 路径已被其他节点占用时，旧 DELETE 不得清理新节点。
          if (state && state.nodeId && state.nodeId !== item.nodeId) break;
          // 进程可能在原件移动后退出；源缺失时也要完成 pending 清单，失败不能推进本页游标。
          const preserve = fs.existsSync(absPath) ? preserveAndRemove : resumeRecoveryIfPresent;
          const recovery = preserve(this.root.rootId, item.logId, this.root.localPath,
            absPath, relPath, (written) => this.markEngineWritten(written));
          if (recovery) syncLog('delete', '云端删除，已保留本地副本: ' + recovery);
          for (const row of getAllSyncStates(this.root.rootId)) {
            if (row.localPath === relPath || row.localPath.startsWith(relPath + '/')) {
              deleteSyncState(this.root.rootId, row.localPath);
            }
          }
          break;
        }

        case 'MOVE':
        case 'RENAME': {
          // 云端移动/重命名 -> 本地同步移动/重命名
          const oldPath = item.oldPath;
          if (!oldPath) break;
          const oldAbs = this.absPathFor(oldPath);
          if (!oldAbs) throw new Error('增量事件旧路径越界');
          const oldState = getSyncState(this.root.rootId, oldPath);

          // 旧路径可能已被其他节点复用，未知身份也不能直接移动或重新绑定。
          if ((oldState && oldState.nodeId !== item.nodeId)
              || (!oldState && fs.existsSync(oldAbs))) {
            if (!(await fullReconcileImpl(this))) throw new Error('移动源身份不匹配，对账失败');
            break;
          }

          // 无意义变更：新旧路径一致（服务端同目录移动/同名重命名产生的脏日志）
          // 只刷新云端元数据，不删除/重建状态，避免 local_mtime 被清空导致反复上传
          if (oldPath === relPath) {
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
              if (isLocallyChanged(oldState, stat.mtimeMs) && !this.isSelfWrite(oldPath, stat.mtimeMs)) {
                const recovery = preserveAndRemove(this.root.rootId, item.logId, this.root.localPath,
                  oldAbs, oldPath, (written) => this.markEngineWritten(written));
                syncLog('conflict', '移动前保留本地修改: ' + recovery);
              }
            }
            const sourcePreserved = !fs.existsSync(oldAbs);
            // 补下载获取当前内容；直接 rename 则保持本地旧内容的真实基线，后续 UPDATE 仍可识别。
            if (sourcePreserved && item.nodeType === 1 && current) {
              item = { ...item, size: current.size, md5: current.md5, updatedAt: current.updatedAt };
            }
            if (fs.existsSync(absPath)) throw new Error('移动目标已存在，停止以保护本地文件');
            const dir = path.dirname(absPath);
            if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
            this.markEngineWritten(oldPath);
            this.markEngineWritten(relPath);
            if (!sourcePreserved) fs.renameSync(oldAbs, absPath);
            else if (item.nodeType === 0) {
              await this.completeMovedDirectory(item, absPath, relPath);
            } else await this.downloadFile(item, absPath, relPath);
            syncLog(item.changeType === 'MOVE' ? 'move' : 'rename',
              (item.changeType === 'MOVE' ? '移动: ' : '重命名: ') + path.basename(oldPath) + ' -> ' + path.basename(relPath));
            // 更新 sync_state：删除旧路径，新路径携带旧状态（保留 local_mtime/md5/size）
            deleteSyncState(this.root.rootId, oldPath);
            upsertSyncState({ rootId: this.root.rootId,
              localPath: relPath,
              nodeId: item.nodeId,
              md5: sourcePreserved ? item.md5 ?? undefined : oldState?.md5 ?? item.md5 ?? undefined,
              size: sourcePreserved ? item.size ?? undefined : oldState?.size ?? item.size ?? undefined,
              localMtime: sourcePreserved ? fs.statSync(absPath).mtimeMs : oldState?.localMtime,
              cloudMtime: item.updatedAt,
              status: 'synced',
            });
            // 文件夹移动：按前缀迁移子孙 sync_state，防止子树被当作新文件重传
            if (item.nodeType === 0 && !sourcePreserved) {
              this.migrateSyncStatePrefix(oldPath, relPath);
            } else if (item.nodeType === 0) {
              for (const row of getAllSyncStates(this.root.rootId)) {
                if (row.localPath.startsWith(oldPath + '/')) deleteSyncState(this.root.rootId, row.localPath);
              }
            }
          } else {
            // 旧文件不存在（可能本地也未同步过），按下载处理
            if (fs.existsSync(absPath)) {
              if (getSyncState(this.root.rootId, relPath)?.nodeId !== item.nodeId) {
                throw new Error('移动目标已存在，停止以保护本地文件');
              }
              if (item.nodeType !== 0) {
                // 目标已完整落地后的崩溃重放仍须清理同节点旧身份，避免旧路径新文件误更新该节点。
                if (getSyncState(this.root.rootId, relPath)?.status !== 'synced') {
                  throw new Error('移动目标尚未完整落地');
                }
                if (oldState?.nodeId === item.nodeId) deleteSyncState(this.root.rootId, oldPath);
                break;
              }
            }
            if (item.nodeType === 0) {
              await this.completeMovedDirectory(item, absPath, relPath);
              // 子树完整落地后才清理旧映射；失败时保留状态供同页重放。
              if (oldState?.nodeId === item.nodeId) {
                for (const row of getAllSyncStates(this.root.rootId)) {
                  if (row.localPath === oldPath || row.localPath.startsWith(oldPath + '/')) {
                    deleteSyncState(this.root.rootId, row.localPath);
                  }
                }
              }
            } else {
              if (current) item = { ...item, size: current.size, md5: current.md5, updatedAt: current.updatedAt };
              await this.downloadFile(item, absPath, relPath);
              // 源已缺失时，目标完整下载后才能清理旧身份；下载失败必须保留映射供重放。
              if (oldState?.nodeId === item.nodeId) deleteSyncState(this.root.rootId, oldPath);
            }
          }
          break;
        }

        case 'CREATE':
        case 'UPDATE':
        default: {
          // 文件夹：确保本地存在
          if (item.nodeType === 0) {
            if (fs.existsSync(absPath) && (!fs.statSync(absPath).isDirectory()
                || state?.nodeId && state.nodeId !== item.nodeId)) {
              throw new Error('云端目录与本地路径冲突');
            }
            if (!fs.existsSync(absPath)) {
              fs.mkdirSync(absPath, { recursive: true });
              syncLog('create', '创建文件夹: ' + path.basename(relPath) + '（云端同步）');
            }
            upsertSyncState({ rootId: this.root.rootId, localPath: relPath, nodeId: item.nodeId, status: 'synced', cloudMtime: item.updatedAt });
            if (item.changeType === 'CREATE') await reconcileFolder(this, item.nodeId, relPath);
            break;
          }

          // 文件：检查冲突
          const localExists = fs.existsSync(absPath);
          const localChanged = localExists && state && (state.localMtime ?? 0) < fs.statSync(absPath).mtimeMs;

          if (localExists && state?.nodeId && state.nodeId !== item.nodeId) {
            await this.handleConflict(absPath, relPath, item);
            continue;
          }

          if (localExists && !state) {
            await this.handleConflict(absPath, relPath, item);
            continue;
          }

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

  private async completeMovedDirectory(item: DeltaItem, absPath: string, relPath: string): Promise<void> {
    if (fs.existsSync(absPath) && !fs.statSync(absPath).isDirectory()) {
      throw new Error('移动目录目标不是目录');
    }
    // 先登记身份再创建目录，网络中断或进程退出后可以识别并继续补齐该子树。
    upsertSyncState({ rootId: this.root.rootId, localPath: relPath,
      nodeId: item.nodeId, status: 'pending', cloudMtime: item.updatedAt });
    this.markEngineWritten(relPath);
    fs.mkdirSync(absPath, { recursive: true });
    await reconcileFolder(this, item.nodeId, relPath);
    upsertSyncState({ rootId: this.root.rootId, localPath: relPath,
      nodeId: item.nodeId, status: 'synced', cloudMtime: item.updatedAt });
  }

  private async readCloudNode(nodeId: string): Promise<{
    path: string; status: number; size: number | null; md5: string | null; updatedAt: string;
  } | null> {
    const response = await withRetry(() => apiClient.get(`/file/${nodeId}`), '读取云端节点');
    const body = response.data;
    if (body?.code === 2001 || body?.code === 2008) return null;
    if (body?.code !== 200 || !body.data || typeof body.data.path !== 'string') {
      throw new Error('云端节点详情不可用');
    }
    if (typeof body.data.updatedAt !== 'string'
        || (body.data.fileMd5 != null && typeof body.data.fileMd5 !== 'string')) {
      throw new Error('云端节点内容元数据无效');
    }
    return { path: body.data.path, status: body.data.status,
      size: parseFileSize(body.data.fileSize), md5: body.data.fileMd5 ?? null,
      updatedAt: body.data.updatedAt };
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
