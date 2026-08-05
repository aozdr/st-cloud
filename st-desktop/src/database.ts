import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import { app } from 'electron';
import path from 'path';
import fs from 'fs';
import type { TransferTask, TransferType, TransferStatus } from './types';

let SQL: SqlJsStatic;
let db: Database;
let dbPath: string;

/** 实时速度内存缓存（不持久化，速度是瞬时值） */
const speedCache = new Map<string, number>();

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
      local_path  TEXT PRIMARY KEY,
      node_id     TEXT,
      md5         TEXT,
      size        INTEGER,
      local_mtime INTEGER,
      cloud_mtime TEXT,
      status      TEXT,
      updated_at  TEXT NOT NULL
    )
  `);

  // 同步根配置（客户端侧：本地路径与游标，按用户隔离）
  db.run(`
    CREATE TABLE IF NOT EXISTS sync_config (
      root_id       TEXT PRIMARY KEY,
      local_path    TEXT NOT NULL,
      cursor        INTEGER DEFAULT 0,
      status        TEXT DEFAULT 'active',
      user_id       TEXT,
      updated_at    TEXT NOT NULL
    )
  `);
  // 迁移：为已有数据库补充 user_id 列
  try { db.run('ALTER TABLE sync_config ADD COLUMN user_id TEXT'); } catch { /* 列已存在 */ }
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
  const values: unknown[] = [];

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
      values.push(key === 'uploadedChunks' && value ? JSON.stringify(value) : value);
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

export interface SyncStateRow {
  localPath: string;
  nodeId?: string;
  md5?: string;
  size?: number;
  localMtime?: number;
  cloudMtime?: string;
  status?: string;
}

export function upsertSyncState(row: SyncStateRow): void {
  const now = new Date().toISOString();
  db.run(
    `INSERT INTO sync_state (local_path, node_id, md5, size, local_mtime, cloud_mtime, status, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(local_path) DO UPDATE SET
       node_id=excluded.node_id, md5=excluded.md5, size=excluded.size,
       local_mtime=excluded.local_mtime, cloud_mtime=excluded.cloud_mtime,
       status=excluded.status, updated_at=excluded.updated_at`,
    [row.localPath, row.nodeId ?? null, row.md5 ?? null, row.size ?? null,
     row.localMtime ?? null, row.cloudMtime ?? null, row.status ?? null, now],
  );
  persist();
}

export function getSyncState(localPath: string): SyncStateRow | null {
  const stmt = db.prepare('SELECT * FROM sync_state WHERE local_path = ?');
  stmt.bind([localPath]);
  let result: SyncStateRow | null = null;
  if (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    result = {
      localPath: row.local_path as string,
      nodeId: (row.node_id as string | null) ?? undefined,
      md5: (row.md5 as string | null) ?? undefined,
      size: (row.size as number | null) ?? undefined,
      localMtime: (row.local_mtime as number | null) ?? undefined,
      cloudMtime: (row.cloud_mtime as string | null) ?? undefined,
      status: (row.status as string | null) ?? undefined,
    };
  }
  stmt.free();
  return result;
}

export function getAllSyncStates(): SyncStateRow[] {
  const stmt = db.prepare('SELECT * FROM sync_state');
  const results: SyncStateRow[] = [];
  while (stmt.step()) {
    const row = stmt.getAsObject() as Record<string, unknown>;
    results.push({
      localPath: row.local_path as string,
      nodeId: (row.node_id as string | null) ?? undefined,
      md5: (row.md5 as string | null) ?? undefined,
      size: (row.size as number | null) ?? undefined,
      localMtime: (row.local_mtime as number | null) ?? undefined,
      cloudMtime: (row.cloud_mtime as string | null) ?? undefined,
      status: (row.status as string | null) ?? undefined,
    });
  }
  stmt.free();
  return results;
}

export function deleteSyncState(localPath: string): void {
  db.run('DELETE FROM sync_state WHERE local_path = ?', [localPath]);
  persist();
}

// ==================== 同步根配置（SyncConfig）====================

export interface SyncConfigRow {
  rootId: string;
  localPath: string;
  cursor: number;
  status: string;
  userId?: string;
}

export function upsertSyncConfig(row: Partial<SyncConfigRow> & { rootId: string; localPath: string }): void {
  const now = new Date().toISOString();
  db.run(
    `INSERT INTO sync_config (root_id, local_path, cursor, status, user_id, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(root_id) DO UPDATE SET
       local_path=excluded.local_path, cursor=excluded.cursor,
       status=excluded.status, user_id=excluded.user_id, updated_at=excluded.updated_at`,
    [row.rootId, row.localPath, row.cursor ?? 0, row.status ?? 'active', row.userId ?? null, now],
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
