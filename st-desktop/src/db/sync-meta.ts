import { getDb, persist } from './db-core';


export interface BlockHashRow {
  blockIndex: number;
  blockMd5: string;
  blockSize: number;
}

/** 读取指定文件的块哈希列表（按 block_index 升序） */
export function getBlockHashes(rootId: string, relPath: string): BlockHashRow[] {
  const stmt = getDb().prepare('SELECT block_index, block_md5, block_size FROM sync_block_hash WHERE root_id = ? AND rel_path = ? ORDER BY block_index');
  stmt.bind([rootId, relPath]);
  const results: BlockHashRow[] = [];
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    results.push({
      blockIndex: row.block_index as number,
      blockMd5: row.block_md5 as string,
      blockSize: row.block_size as number,
    });
  }
  stmt.free();
  return results;
}

/** 覆盖写入指定文件的块哈希列表（先删后插，保持幂等） */
export function setBlockHashes(rootId: string, relPath: string, blocks: BlockHashRow[]): void {
  getDb().run('DELETE FROM sync_block_hash WHERE root_id = ? AND rel_path = ?', [rootId, relPath]);
  const stmt = getDb().prepare('INSERT INTO sync_block_hash (root_id, rel_path, block_index, block_md5, block_size) VALUES (?, ?, ?, ?, ?)');
  for (const b of blocks) {
    stmt.bind([rootId, relPath, b.blockIndex, b.blockMd5, b.blockSize]);
    stmt.step();
    stmt.reset();
  }
  stmt.free();
  persist();
}

/** 删除指定文件的块哈希缓存 */
export function deleteBlockHashes(rootId: string, relPath: string): void {
  getDb().run('DELETE FROM sync_block_hash WHERE root_id = ? AND rel_path = ?', [rootId, relPath]);
  persist();
}

/** 将内存数据库写入磁盘文件 */


// ==================== 同步状态（SyncState）====================

export interface SyncHistoryRow {
  id: number;
  rootId: string;
  action: string;
  fileName: string | null;
  relPath: string | null;
  status: string;
  detail: string | null;
  createdAt: string;
}

export interface SyncHistoryInput {
  rootId: string;
  action: string;
  fileName?: string | null;
  relPath?: string | null;
  status: string;
  detail?: string | null;
}

export function insertSyncHistory(row: SyncHistoryInput): void {
  const now = new Date().toISOString();
  getDb().run(
    `INSERT INTO sync_history (root_id, action, file_name, rel_path, status, detail, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [row.rootId, row.action, row.fileName ?? null, row.relPath ?? null, row.status, row.detail ?? null, now],
  );
  persist();
}

export function getSyncHistory(rootId: string, limit = 100): SyncHistoryRow[] {
  const stmt = getDb().prepare('SELECT * FROM sync_history WHERE root_id = ? ORDER BY created_at DESC LIMIT ?');
  stmt.bind([rootId, limit]);
  const results: SyncHistoryRow[] = [];
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    results.push({
      id: row.id as number,
      rootId: row.root_id as string,
      action: row.action as string,
      fileName: (row.file_name as string | null) ?? null,
      relPath: (row.rel_path as string | null) ?? null,
      status: row.status as string,
      detail: (row.detail as string | null) ?? null,
      createdAt: row.created_at as string,
    });
  }
  stmt.free();
  return results;
}

export function getSyncStats(rootId: string): { synced: number; error: number; conflict: number; excluded: number } {
  const stmt = getDb().prepare(`SELECT status, COUNT(*) as cnt FROM sync_state WHERE root_id = ? AND status IS NOT NULL GROUP BY status`);
  stmt.bind([rootId]);
  const stats = { synced: 0, error: 0, conflict: 0, excluded: 0 };
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    const status = row.status as string;
    const cnt = row.cnt as number;
    if (status === 'synced') stats.synced += cnt;
    else if (status === 'error') stats.error += cnt;
    else if (status === 'conflict') stats.conflict += cnt;
    else if (status === 'excluded') stats.excluded += cnt;
  }
  stmt.free();
  return stats;
}

export interface SyncStateRow {
  /** 同步根 ID：sync_state 以 (root_id, local_path) 为唯一键，跨同步根状态隔离 */
  rootId: string;
  localPath: string;
  nodeId?: string;
  md5?: string;
  size?: number;
  localMtime?: number;
  cloudMtime?: string;
  status?: string;
  /** 连续失败次数（0=无失败记录），用于上传失败指数退避 */
  failCount?: number;
  /** 失败时的本地文件 mtime（ms），mtime 变化视为用户再次修改，立即重试 */
  failMtime?: number;
  /** 下次允许重试的 epoch ms，未到前跳过该文件 */
  nextRetryAt?: number;
}

export function upsertSyncState(row: SyncStateRow): void {
  const now = new Date().toISOString();
  getDb().run(
    `INSERT INTO sync_state (root_id, local_path, node_id, md5, size, local_mtime, cloud_mtime, status, fail_count, fail_mtime, next_retry_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(root_id, local_path) DO UPDATE SET
       node_id=COALESCE(excluded.node_id, sync_state.node_id),
       md5=COALESCE(excluded.md5, sync_state.md5),
       size=COALESCE(excluded.size, sync_state.size),
       local_mtime=COALESCE(excluded.local_mtime, sync_state.local_mtime),
       cloud_mtime=COALESCE(excluded.cloud_mtime, sync_state.cloud_mtime),
       status=COALESCE(excluded.status, sync_state.status),
       fail_count=COALESCE(excluded.fail_count, sync_state.fail_count),
       fail_mtime=COALESCE(excluded.fail_mtime, sync_state.fail_mtime),
       next_retry_at=COALESCE(excluded.next_retry_at, sync_state.next_retry_at),
       updated_at=excluded.updated_at`,
    [row.rootId, row.localPath, row.nodeId ?? null, row.md5 ?? null, row.size ?? null,
     row.localMtime ?? null, row.cloudMtime ?? null, row.status ?? null,
     row.failCount ?? 0, row.failMtime ?? null, row.nextRetryAt ?? null, now],
  );
  persist();
}

export function getSyncState(rootId: string, localPath: string): SyncStateRow | null {
  const stmt = getDb().prepare('SELECT * FROM sync_state WHERE root_id = ? AND local_path = ?');
  stmt.bind([rootId, localPath]);
  let result: SyncStateRow | null = null;
  if (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    result = {
      rootId: row.root_id as string,
      localPath: row.local_path as string,
      nodeId: (row.node_id as string | null) ?? undefined,
      md5: (row.md5 as string | null) ?? undefined,
      size: (row.size as number | null) ?? undefined,
      localMtime: (row.local_mtime as number | null) ?? undefined,
      cloudMtime: (row.cloud_mtime as string | null) ?? undefined,
      status: (row.status as string | null) ?? undefined,
      failCount: (row.fail_count as number | null) ?? undefined,
      failMtime: (row.fail_mtime as number | null) ?? undefined,
      nextRetryAt: (row.next_retry_at as number | null) ?? undefined,
    };
  }
  stmt.free();
  return result;
}

export function getAllSyncStates(rootId: string): SyncStateRow[] {
  const stmt = getDb().prepare('SELECT * FROM sync_state WHERE root_id = ?');
  stmt.bind([rootId]);
  const results: SyncStateRow[] = [];
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    results.push({
      rootId: row.root_id as string,
      localPath: row.local_path as string,
      nodeId: (row.node_id as string | null) ?? undefined,
      md5: (row.md5 as string | null) ?? undefined,
      size: (row.size as number | null) ?? undefined,
      localMtime: (row.local_mtime as number | null) ?? undefined,
      cloudMtime: (row.cloud_mtime as string | null) ?? undefined,
      status: (row.status as string | null) ?? undefined,
      failCount: (row.fail_count as number | null) ?? undefined,
      failMtime: (row.fail_mtime as number | null) ?? undefined,
      nextRetryAt: (row.next_retry_at as number | null) ?? undefined,
    });
  }
  stmt.free();
  return results;
}

export function deleteSyncState(rootId: string, localPath: string): void {
  getDb().run('DELETE FROM sync_state WHERE root_id = ? AND local_path = ?', [rootId, localPath]);
  persist();
}

/** 删除某同步根的全部本地状态（删除同步根时调用，防止旧状态污染新配置） */
export function deleteSyncStatesByRoot(rootId: string): void {
  getDb().run('DELETE FROM sync_state WHERE root_id = ?', [rootId]);
  persist();
}

/** 删除某同步根的块哈希缓存 */
export function deleteBlockHashesByRoot(rootId: string): void {
  getDb().run('DELETE FROM sync_block_hash WHERE root_id = ?', [rootId]);
  persist();
}

/** 删除某同步根的同步历史 */
export function deleteSyncHistoryByRoot(rootId: string): void {
  getDb().run('DELETE FROM sync_history WHERE root_id = ?', [rootId]);
  persist();
}

// ==================== 同步根配置（SyncConfig）====================

export interface SyncConfigRow {
  rootId: string;
  localPath: string;
  cursor: number;
  status: string;
  userId?: string;
  /** 最后成功同步时间（epoch ms），仅用于展示/审计，增量判定仍以 cursor 为准 */
  lastSyncAt?: number;
  /** 同步引擎版本：与当前版本不一致时触发一次全量重建 */
  syncVersion?: number;
}

export function upsertSyncConfig(row: Partial<SyncConfigRow> & { rootId: string; localPath: string }): void {
  const now = new Date().toISOString();
  getDb().run(
    `INSERT INTO sync_config (root_id, local_path, cursor, status, user_id, last_sync_at, sync_version, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(root_id) DO UPDATE SET
       local_path=excluded.local_path, cursor=excluded.cursor,
       status=excluded.status,
       user_id=COALESCE(excluded.user_id, sync_config.user_id),
       last_sync_at=COALESCE(excluded.last_sync_at, sync_config.last_sync_at),
       sync_version=COALESCE(excluded.sync_version, sync_config.sync_version),
       updated_at=excluded.updated_at`,
    [row.rootId, row.localPath, row.cursor ?? 0, row.status ?? 'active', row.userId ?? null,
     row.lastSyncAt ?? null, row.syncVersion ?? null, now],
  );
  persist();
}

export function getSyncConfig(rootId: string): SyncConfigRow | null {
  const stmt = getDb().prepare('SELECT * FROM sync_config WHERE root_id = ?');
  stmt.bind([rootId]);
  let result: SyncConfigRow | null = null;
  if (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    result = {
      rootId: row.root_id as string,
      localPath: row.local_path as string,
      cursor: (row.cursor as number | null) ?? 0,
      status: (row.status as string | null) ?? 'active',
      lastSyncAt: (row.last_sync_at as number | null) ?? undefined,
      syncVersion: (row.sync_version as number | null) ?? undefined,
    };
  }
  stmt.free();
  return result;
}

export function getAllSyncConfigs(userId?: string): SyncConfigRow[] {
  const stmt = userId
    ? getDb().prepare('SELECT * FROM sync_config WHERE user_id = ?')
    : getDb().prepare('SELECT * FROM sync_config');
  if (userId) stmt.bind([userId]);
  const results: SyncConfigRow[] = [];
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    results.push({
      rootId: row.root_id as string,
      localPath: row.local_path as string,
      cursor: (row.cursor as number | null) ?? 0,
      status: (row.status as string | null) ?? 'active',
      userId: (row.user_id as string | null) ?? undefined,
      lastSyncAt: (row.last_sync_at as number | null) ?? undefined,
      syncVersion: (row.sync_version as number | null) ?? undefined,
    });
  }
  stmt.free();
  return results;
}

export function deleteSyncConfig(rootId: string): void {
  getDb().run('DELETE FROM sync_config WHERE root_id = ?', [rootId]);
  persist();
}

/** 迁移：将 user_id 为空的旧配置认领给当前用户（一次性） */
export function claimLegacySyncConfigs(userId: string): void {
  getDb().run('UPDATE sync_config SET user_id = ? WHERE user_id IS NULL', [userId]);
  persist();
}

/**
 * 全量重置本地数据库（同步引擎升级/重建用）。
 * 清空传输任务与同步元数据；保留 sync_config（含游标）仅清版本/时间标记，
 * 使每个同步根都触发一次全量重建，且重建后从重建前游标继续增量，避免重放整段历史日志。
 */
export function resetSyncData(): void {
  getDb().run('DELETE FROM transfer_tasks');
  getDb().run('DELETE FROM sync_state');
  getDb().run('DELETE FROM sync_history');
  getDb().run('DELETE FROM sync_block_hash');
  getDb().run('UPDATE sync_config SET sync_version = NULL, last_sync_at = NULL');
  persist();
}
