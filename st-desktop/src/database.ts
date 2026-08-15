import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import { app } from 'electron';
import path from 'path';
import fs from 'fs';
import type { TransferTask, TransferType, TransferStatus } from './types';
import { ensureIdColumnsText } from './db-migrate';

let SQL: SqlJsStatic;
let db: Database;
let dbPath: string;

/** 实时速度内存缓存（不持久化，速度是瞬时值） */
const speedCache = new Map<string, number>();

/**
 * 确保 sync_state 表带 root_id 主键（旧版仅 local_path，跨同步根污染）。
 * 旧数据按项目约定可删：缺少 root_id 列时直接重建空表。
 */
function ensureSyncStateRootId(): void {
  const res = db.exec('PRAGMA table_info(sync_state)');
  const cols = res?.[0]?.values ?? [];
  const hasRootId = cols.some((row) => String(row[1]) === 'root_id');
  if (hasRootId) return;
  db.run('DROP TABLE sync_state');
  db.run(`
    CREATE TABLE sync_state (
      root_id       TEXT NOT NULL,
      local_path    TEXT NOT NULL,
      node_id       TEXT,
      md5           TEXT,
      size          INTEGER,
      local_mtime   INTEGER,
      cloud_mtime   TEXT,
      status        TEXT,
      fail_count    INTEGER NOT NULL DEFAULT 0,
      fail_mtime    REAL,
      next_retry_at INTEGER,
      updated_at    TEXT NOT NULL,
      PRIMARY KEY (root_id, local_path)
    )
  `);
}

export async function initDatabase(): Promise<void> {
  // 定位 wasm 文件（开发模式下在 node_modules 中）
  const wasmFile = 'sql-wasm.wasm';
  const candidates = [
    path.join(__dirname, '..', 'node_modules', 'sql.js', 'dist', wasmFile),
    path.join(app.getAppPath(), 'node_modules', 'sql.js', 'dist', wasmFile),
    path.join(process.resourcesPath || '', wasmFile),
  ];
  const wasmPath = candidates.find((p) => fs.existsSync(p)) || wasmFile;

  SQL = await initSqlJs({ locateFile: () => wasmPath });

  dbPath = path.join(app.getPath('userData'), 'transfers.db');

  // 加载已有数据库或创建新库
  if (fs.existsSync(dbPath)) {
    const buffer = fs.readFileSync(dbPath);
    db = new SQL.Database(new Uint8Array(buffer));
  } else {
    db = new SQL.Database();
  }

  db.run(`
    CREATE TABLE IF NOT EXISTS transfer_tasks (
      id TEXT PRIMARY KEY,
      type TEXT NOT NULL,
      status TEXT NOT NULL,
      file_name TEXT NOT NULL,
      file_size INTEGER NOT NULL,
      transferred_bytes INTEGER DEFAULT 0,
      progress INTEGER DEFAULT 0,
      error TEXT,
      file_path TEXT,
      parent_id TEXT,
      upload_id TEXT,
      s3_upload_id TEXT,
      file_id TEXT,
      total_chunks INTEGER,
      uploaded_chunks TEXT,
      node_id TEXT,
      save_path TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);

  // 同步状态表
  db.run(`
    CREATE TABLE IF NOT EXISTS sync_state (
      root_id       TEXT NOT NULL,
      local_path    TEXT NOT NULL,
      node_id       TEXT,
      md5           TEXT,
      size          INTEGER,
      local_mtime   INTEGER,
      cloud_mtime   TEXT,
      status        TEXT,
      fail_count    INTEGER NOT NULL DEFAULT 0,
      fail_mtime    REAL,
      next_retry_at INTEGER,
      updated_at    TEXT NOT NULL,
      PRIMARY KEY (root_id, local_path)
    )
  `);
  // 迁移：旧版 sync_state 仅以 local_path 为主键（无 root_id），
  // 重新配置同步根后旧状态会污染新 root（死循环根因之一）。老数据可删，直接重建表。
  ensureSyncStateRootId();
  // 迁移：为已有 sync_state 补充失败退避列（幂等，列已存在时抛错忽略）
  try { db.run('ALTER TABLE sync_state ADD COLUMN fail_count INTEGER NOT NULL DEFAULT 0'); } catch { /* 列已存在 */ }
  try { db.run('ALTER TABLE sync_state ADD COLUMN fail_mtime REAL'); } catch { /* 列已存在 */ }
  try { db.run('ALTER TABLE sync_state ADD COLUMN next_retry_at INTEGER'); } catch { /* 列已存在 */ }

  // 同步根配置（客户端侧：本地路径与游标，按用户隔离）
  db.run(`
    CREATE TABLE IF NOT EXISTS sync_config (
      root_id       TEXT PRIMARY KEY,
      local_path    TEXT NOT NULL,
      cursor        INTEGER DEFAULT 0,
      status        TEXT DEFAULT 'active',
      user_id       TEXT,
      last_sync_at  INTEGER,
      sync_version  INTEGER,
      updated_at    TEXT NOT NULL
    )
  `);
  // 迁移：为已有数据库补充 user_id 列
  try { db.run('ALTER TABLE sync_config ADD COLUMN user_id TEXT'); } catch { /* 列已存在 */ }
  // 迁移：为已有数据库补充最后同步时间列
  try { db.run('ALTER TABLE sync_config ADD COLUMN last_sync_at INTEGER'); } catch { /* 列已存在 */ }
  // 迁移：为已有数据库补充同步引擎版本列（版本不一致触发一次全量重建）
  try { db.run('ALTER TABLE sync_config ADD COLUMN sync_version INTEGER'); } catch { /* 列已存在 */ }

  // 同步历史记录表（持久化已完成的上传/下载/删除/冲突操作）
  db.run(`
    CREATE TABLE IF NOT EXISTS sync_history (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      root_id     TEXT NOT NULL,
      action      TEXT NOT NULL,
      file_name   TEXT,
      rel_path    TEXT,
      status      TEXT NOT NULL,
      detail      TEXT,
      created_at  TEXT NOT NULL
    )
  `);
  // 迁移：为已有 sync_history 补充索引
  try { db.run('CREATE INDEX IF NOT EXISTS idx_sync_history_root ON sync_history(root_id, created_at DESC)'); } catch { /* ignore */ }

  // 块级增量同步：持久化本地文件的块哈希缓存（root/rel_path/block_index 维度）
  db.run(`
    CREATE TABLE IF NOT EXISTS sync_block_hash (
      root_id     TEXT NOT NULL,
      rel_path    TEXT NOT NULL,
      block_index INTEGER NOT NULL,
      block_md5   TEXT NOT NULL,
      block_size  INTEGER NOT NULL,
      PRIMARY KEY (root_id, rel_path, block_index)
    )
  `);

  // 迁移：旧库 ID 列为 INTEGER 亲和性，sql.js 读回 JS Number 会丢精度（雪花ID>2^53），
  // 重建为 TEXT 使读回为完整字符串（SQLite 64位整数转 TEXT 无损，可自愈历史污染）
  const rebuilt = ensureIdColumnsText(db);
  if (rebuilt.length > 0) {
    console.log('[db] ID 列亲和性迁移完成:', rebuilt.join(', '));
  }

  persist();
}

export interface BlockHashRow {
  blockIndex: number;
  blockMd5: string;
  blockSize: number;
}

/** 读取指定文件的块哈希列表（按 block_index 升序） */
export function getBlockHashes(rootId: string, relPath: string): BlockHashRow[] {
  const stmt = db.prepare('SELECT block_index, block_md5, block_size FROM sync_block_hash WHERE root_id = ? AND rel_path = ? ORDER BY block_index');
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
  db.run('DELETE FROM sync_block_hash WHERE root_id = ? AND rel_path = ?', [rootId, relPath]);
  const stmt = db.prepare('INSERT INTO sync_block_hash (root_id, rel_path, block_index, block_md5, block_size) VALUES (?, ?, ?, ?, ?)');
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
  db.run('DELETE FROM sync_block_hash WHERE root_id = ? AND rel_path = ?', [rootId, relPath]);
  persist();
}

/** 将内存数据库写入磁盘文件 */
function persist(): void {
  const data = db.export();
  fs.writeFileSync(dbPath, Buffer.from(data));
}

interface TaskRow {
  id: string;
  type: string;
  status: string;
  file_name: string;
  file_size: number;
  transferred_bytes: number;
  progress: number;
  error: string | null;
  file_path: string | null;
  parent_id: string | null;
  upload_id: string | null;
  s3_upload_id: string | null;
  file_id: string | null;
  total_chunks: number | null;
  uploaded_chunks: string | null;
  node_id: string | null;
  save_path: string | null;
  created_at: string;
  updated_at: string;
}

function rowToTask(row: Record<string, unknown>): TransferTask {
  return {
    id: row.id as string,
    type: row.type as TransferType,
    status: row.status as TransferStatus,
    fileName: row.file_name as string,
    fileSize: row.file_size as number,
    transferredBytes: row.transferred_bytes as number,
    progress: row.progress as number,
    speed: speedCache.get(row.id as string) ?? 0,
    error: (row.error as string | null) ?? null,
    createdAt: row.created_at as string,
    filePath: (row.file_path as string | null) ?? undefined,
    parentId: (row.parent_id as string | null) ?? undefined,
    uploadId: (row.upload_id as string | null) ?? undefined,
    s3UploadId: (row.s3_upload_id as string | null) ?? undefined,
    fileId: (row.file_id as string | null) ?? undefined,
    totalChunks: (row.total_chunks as number | null) ?? undefined,
    uploadedChunks: row.uploaded_chunks
      ? (JSON.parse(row.uploaded_chunks as string) as number[])
      : undefined,
    nodeId: (row.node_id as string | null) ?? undefined,
    savePath: (row.save_path as string | null) ?? undefined,
  };
}

export function createTask(task: TransferTask): void {
  const now = new Date().toISOString();
  db.run(
    `INSERT INTO transfer_tasks
      (id, type, status, file_name, file_size, transferred_bytes, progress, error,
       file_path, parent_id, upload_id, s3_upload_id, file_id, total_chunks, uploaded_chunks,
       node_id, save_path, created_at, updated_at)
    VALUES
      (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      task.id,
      task.type,
      task.status,
      task.fileName,
      task.fileSize,
      task.transferredBytes,
      task.progress,
      task.error ?? null,
      task.filePath ?? null,
      task.parentId ?? null,
      task.uploadId ?? null,
      task.s3UploadId ?? null,
      task.fileId ?? null,
      task.totalChunks ?? null,
      task.uploadedChunks ? JSON.stringify(task.uploadedChunks) : null,
      task.nodeId ?? null,
      task.savePath ?? null,
      task.createdAt || now,
      now,
    ],
  );
  persist();
}

export function updateTask(id: string, fields: Partial<TransferTask>): void {
  // speed 只缓存到内存（瞬时值，不持久化到数据库）
  if (fields.speed !== undefined) {
    speedCache.set(id, fields.speed);
  }

  const sets: string[] = [];
  const values: Array<string | number | null> = [];

  const fieldMap: Record<string, string> = {
    status: 'status',
    transferredBytes: 'transferred_bytes',
    progress: 'progress',
    error: 'error',
    uploadId: 'upload_id',
    s3UploadId: 's3_upload_id',
    fileId: 'file_id',
    totalChunks: 'total_chunks',
    uploadedChunks: 'uploaded_chunks',
  };

  for (const [key, value] of Object.entries(fields)) {
    const col = fieldMap[key];
    if (col) {
      sets.push(`${col} = ?`);
      values.push(key === 'uploadedChunks' && value ? JSON.stringify(value) : ((value as string | number | null | undefined) ?? null));
    }
  }

  if (sets.length === 0) return;
  sets.push('updated_at = ?');
  values.push(new Date().toISOString());
  values.push(id);

  db.run(`UPDATE transfer_tasks SET ${sets.join(', ')} WHERE id = ?`, values);
  persist();
}

export function deleteTask(id: string): void {
  speedCache.delete(id);
  db.run('DELETE FROM transfer_tasks WHERE id = ?', [id]);
  persist();
}

export function getTask(id: string): TransferTask | null {
  const stmt = db.prepare('SELECT * FROM transfer_tasks WHERE id = ?');
  stmt.bind([id]);
  let result: TransferTask | null = null;
  if (stmt.step()) {
    result = rowToTask(stmt.getAsObject());
  }
  stmt.free();
  return result;
}

export function getAllTasks(): TransferTask[] {
  const stmt = db.prepare('SELECT * FROM transfer_tasks ORDER BY created_at DESC');
  const results: TransferTask[] = [];
  while (stmt.step()) {
    results.push(rowToTask(stmt.getAsObject()));
  }
  stmt.free();
  return results;
}

export function getPendingTasks(): TransferTask[] {
  const stmt = db.prepare(
    `SELECT * FROM transfer_tasks WHERE status IN ('uploading', 'downloading', 'paused', 'pending', 'hashing') ORDER BY created_at`,
  );
  const results: TransferTask[] = [];
  while (stmt.step()) {
    results.push(rowToTask(stmt.getAsObject()));
  }
  stmt.free();
  return results;
}

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
  db.run(
    `INSERT INTO sync_history (root_id, action, file_name, rel_path, status, detail, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [row.rootId, row.action, row.fileName ?? null, row.relPath ?? null, row.status, row.detail ?? null, now],
  );
  persist();
}

export function getSyncHistory(rootId: string, limit = 100): SyncHistoryRow[] {
  const stmt = db.prepare('SELECT * FROM sync_history WHERE root_id = ? ORDER BY created_at DESC LIMIT ?');
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
  const stmt = db.prepare(`SELECT status, COUNT(*) as cnt FROM sync_state WHERE root_id = ? AND status IS NOT NULL GROUP BY status`);
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
  db.run(
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
  const stmt = db.prepare('SELECT * FROM sync_state WHERE root_id = ? AND local_path = ?');
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
  const stmt = db.prepare('SELECT * FROM sync_state WHERE root_id = ?');
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
  db.run('DELETE FROM sync_state WHERE root_id = ? AND local_path = ?', [rootId, localPath]);
  persist();
}

/** 删除某同步根的全部本地状态（删除同步根时调用，防止旧状态污染新配置） */
export function deleteSyncStatesByRoot(rootId: string): void {
  db.run('DELETE FROM sync_state WHERE root_id = ?', [rootId]);
  persist();
}

/** 删除某同步根的块哈希缓存 */
export function deleteBlockHashesByRoot(rootId: string): void {
  db.run('DELETE FROM sync_block_hash WHERE root_id = ?', [rootId]);
  persist();
}

/** 删除某同步根的同步历史 */
export function deleteSyncHistoryByRoot(rootId: string): void {
  db.run('DELETE FROM sync_history WHERE root_id = ?', [rootId]);
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
  db.run(
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
  const stmt = db.prepare('SELECT * FROM sync_config WHERE root_id = ?');
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
    ? db.prepare('SELECT * FROM sync_config WHERE user_id = ?')
    : db.prepare('SELECT * FROM sync_config');
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
  db.run('DELETE FROM sync_config WHERE root_id = ?', [rootId]);
  persist();
}

/** 迁移：将 user_id 为空的旧配置认领给当前用户（一次性） */
export function claimLegacySyncConfigs(userId: string): void {
  db.run('UPDATE sync_config SET user_id = ? WHERE user_id IS NULL', [userId]);
  persist();
}

/**
 * 全量重置本地数据库（同步引擎升级/重建用）。
 * 清空传输任务与同步元数据；保留 sync_config（含游标）仅清版本/时间标记，
 * 使每个同步根都触发一次全量重建，且重建后从重建前游标继续增量，避免重放整段历史日志。
 */
export function resetSyncData(): void {
  db.run('DELETE FROM transfer_tasks');
  db.run('DELETE FROM sync_state');
  db.run('DELETE FROM sync_history');
  db.run('DELETE FROM sync_block_hash');
  db.run('UPDATE sync_config SET sync_version = NULL, last_sync_at = NULL');
  persist();
}
