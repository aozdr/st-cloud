import type { Database } from 'sql.js';

/**
 * 本地库表结构迁移（纯函数，便于单元测试；不依赖 Electron）。
 * <p>
 * 背景：早期版本的 sync_state.node_id / sync_config.root_id 是 INTEGER 亲和性，
 * 写入正确的字符串雪花ID时被 SQLite 存为 64 位整数（无损），但 sql.js 读回时转成 JS Number，
 * 超过 2^53 丢精度（如 2087445337642287105 -> 2087445337642287000），导致后续所有 API 请求
 * 使用被污染的 ID（block-check/replaceFileId 报"文件不存在"、同步死循环）。
 * <p>
 * 本模块将这两列重建为 TEXT 亲和性：SQLite 中 64 位整数转 TEXT 是无损的，迁移后读回即为
 * 完全正确的字符串 ID，无需服务端参与即可自愈历史数据。
 */

const SYNC_STATE_SCHEMA = `
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
`;

const SYNC_CONFIG_SCHEMA = `
  root_id       TEXT PRIMARY KEY,
  local_path    TEXT NOT NULL,
  cursor        INTEGER DEFAULT 0,
  status        TEXT DEFAULT 'active',
  user_id       TEXT,
  last_sync_at  INTEGER,
  sync_version  INTEGER,
  updated_at    TEXT NOT NULL
`;

const SYNC_STATE_COLUMNS =
  'root_id, local_path, node_id, md5, size, local_mtime, cloud_mtime, status, fail_count, fail_mtime, next_retry_at, updated_at';
const SYNC_CONFIG_COLUMNS = 'root_id, local_path, cursor, status, user_id, last_sync_at, sync_version, updated_at';

/** 读取指定列在表中的声明类型（大写）；表或列不存在返回 null */
function columnType(db: Database, table: string, column: string): string | null {
  const res = db.exec(`PRAGMA table_info(${table})`);
  if (!res || res.length === 0) return null;
  const { columns, values } = res[0];
  const nameIdx = columns.indexOf('name');
  const typeIdx = columns.indexOf('type');
  for (const row of values) {
    if (String(row[nameIdx]) === column) {
      return String(row[typeIdx]).toUpperCase();
    }
  }
  return null;
}

/** 重建表为指定 schema（建新表 -> 拷贝 -> 删旧表 -> 改名），保留数据 */
function rebuildTable(db: Database, table: string, schema: string, columns: string): void {
  const newTable = table + '_new';
  db.run(`DROP TABLE IF EXISTS ${newTable}`);
  db.run(`CREATE TABLE ${newTable} (${schema})`);
  db.run(`INSERT INTO ${newTable} (${columns}) SELECT ${columns} FROM ${table}`);
  db.run(`DROP TABLE ${table}`);
  db.run(`ALTER TABLE ${newTable} RENAME TO ${table}`);
}

/**
 * 确保 ID 列使用 TEXT 亲和性（幂等）。
 * @returns 实际重建的表名列表（空 = 无需迁移）
 */
export function ensureIdColumnsText(db: Database): string[] {
  const rebuilt: string[] = [];
  if (columnType(db, 'sync_state', 'node_id') !== 'TEXT') {
    rebuildTable(db, 'sync_state', SYNC_STATE_SCHEMA, SYNC_STATE_COLUMNS);
    rebuilt.push('sync_state');
  }
  if (columnType(db, 'sync_config', 'root_id') !== 'TEXT') {
    rebuildTable(db, 'sync_config', SYNC_CONFIG_SCHEMA, SYNC_CONFIG_COLUMNS);
    rebuilt.push('sync_config');
  }
  return rebuilt;
}
