import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import { app } from 'electron';
import path from 'path';
import fs from 'fs';
import { ensureIdColumnsText } from './db-migrate';
import { setDb, getDb, persist } from './db/db-core';

// ==================== 初始化与连接管理（域实现在 ./db/） ====================

function ensureSyncStateRootId(): void {
  const res = getDb().exec('PRAGMA table_info(sync_state)');
  const cols = res?.[0]?.values ?? [];
  const hasRootId = cols.some((row) => String(row[1]) === 'root_id');
  if (hasRootId) return;
  getDb().run('DROP TABLE sync_state');
  getDb().run(`
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

  const SQL = await initSqlJs({ locateFile: () => wasmPath });

  const dbPath = path.join(app.getPath('userData'), 'transfers.db');

  // 加载已有数据库或创建新库
  if (fs.existsSync(dbPath)) {
    const buffer = fs.readFileSync(dbPath);
    setDb(new SQL.Database(new Uint8Array(buffer)), dbPath);
  } else {
    setDb(new SQL.Database(), dbPath);
  }

  getDb().run(`
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
  getDb().run(`
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
  try { getDb().run('ALTER TABLE sync_state ADD COLUMN fail_count INTEGER NOT NULL DEFAULT 0'); } catch { /* 列已存在 */ }
  try { getDb().run('ALTER TABLE sync_state ADD COLUMN fail_mtime REAL'); } catch { /* 列已存在 */ }
  try { getDb().run('ALTER TABLE sync_state ADD COLUMN next_retry_at INTEGER'); } catch { /* 列已存在 */ }

  // 同步根配置（客户端侧：本地路径与游标，按用户隔离）
  getDb().run(`
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
  try { getDb().run('ALTER TABLE sync_config ADD COLUMN user_id TEXT'); } catch { /* 列已存在 */ }
  // 迁移：为已有数据库补充最后同步时间列
  try { getDb().run('ALTER TABLE sync_config ADD COLUMN last_sync_at INTEGER'); } catch { /* 列已存在 */ }
  // 迁移：为已有数据库补充同步引擎版本列（版本不一致触发一次全量重建）
  try { getDb().run('ALTER TABLE sync_config ADD COLUMN sync_version INTEGER'); } catch { /* 列已存在 */ }

  // 同步历史记录表（持久化已完成的上传/下载/删除/冲突操作）
  getDb().run(`
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
  try { getDb().run('CREATE INDEX IF NOT EXISTS idx_sync_history_root ON sync_history(root_id, created_at DESC)'); } catch { /* ignore */ }

  // 块级增量同步：持久化本地文件的块哈希缓存（root/rel_path/block_index 维度）
  getDb().run(`
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
  const rebuilt = ensureIdColumnsText(getDb());
  if (rebuilt.length > 0) {
    console.log('[db] ID 列亲和性迁移完成:', rebuilt.join(', '));
  }

  persist();
}

// ==================== 域模块 re-export（对外 API 保持不变） ====================
export {
  getBlockHashes, setBlockHashes, deleteBlockHashes,
  insertSyncHistory, getSyncHistory, getSyncStats,
  upsertSyncState, getSyncState, getAllSyncStates, deleteSyncState,
  deleteSyncStatesByRoot, deleteBlockHashesByRoot, deleteSyncHistoryByRoot,
  upsertSyncConfig, getSyncConfig, getAllSyncConfigs, deleteSyncConfig,
  claimLegacySyncConfigs, resetSyncData,
} from './db/sync-meta';
export {
  createTask, updateTask, deleteTask, getTask, getAllTasks, getPendingTasks,
} from './db/transfer-tasks';
