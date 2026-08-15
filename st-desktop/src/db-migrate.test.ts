import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import { ensureIdColumnsText } from './db-migrate';

let sqlPromise: Promise<SqlJsStatic> | null = null;
function getSQL(): Promise<SqlJsStatic> {
  if (!sqlPromise) {
    sqlPromise = initSqlJs({
      locateFile: () => path.join(__dirname, '..', 'node_modules', 'sql.js', 'dist', 'sql-wasm.wasm'),
    });
  }
  return sqlPromise;
}

/** 构造旧版（INTEGER 亲和性）schema 的库，模拟历史 transfers.db */
async function createOldSchemaDb(): Promise<Database> {
  const SQL = await getSQL();
  const db = new SQL.Database();
  db.run(`
    CREATE TABLE sync_state (
      root_id     TEXT,
      local_path  TEXT PRIMARY KEY,
      node_id     INTEGER,
      md5         TEXT,
      size        INTEGER,
      local_mtime INTEGER,
      cloud_mtime TEXT,
      status      TEXT,
      updated_at  TEXT NOT NULL,
      fail_count  INTEGER NOT NULL DEFAULT 0,
      fail_mtime  REAL,
      next_retry_at INTEGER
    )
  `);
  db.run(`
    CREATE TABLE sync_config (
      root_id      INTEGER PRIMARY KEY,
      local_path   TEXT NOT NULL,
      cursor       INTEGER DEFAULT 0,
      status       TEXT DEFAULT 'active',
      updated_at   TEXT NOT NULL,
      user_id      TEXT,
      last_sync_at INTEGER,
      sync_version INTEGER
    )
  `);
  return db;
}

test('INTEGER 亲和性导致雪花ID读回丢精度（bug 复现）', async () => {
  const db = await createOldSchemaDb();
  // 写入正确的字符串 ID
  db.run('INSERT INTO sync_state (root_id, local_path, node_id, md5, updated_at) VALUES (?, ?, ?, ?, ?)', [
    '2083478593059856385', '/template920.zip', '2087445337642287105', 'dcedb76d1857e0e703fe07c73c3e6242', '2026-08-15T00:00:00.000Z',
  ]);
  const stmt = db.prepare("SELECT node_id FROM sync_state WHERE local_path = '/template920.zip'");
  stmt.step();
  const row = stmt.getAsObject() as { node_id: unknown };
  stmt.free();
  assert.equal(typeof row.node_id, 'number');
  assert.equal(String(row.node_id), '2087445337642287000');
});

test('ensureIdColumnsText 重建后雪花ID读回完整字符串', async () => {
  const db = await createOldSchemaDb();
  db.run(
    `INSERT INTO sync_state
       (root_id, local_path, node_id, md5, size, local_mtime, cloud_mtime, status, updated_at, fail_count, fail_mtime, next_retry_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ['2083478593059856385', '/template920.zip', '2087445337642287105', 'dcedb76d1857e0e703fe07c73c3e6242', 39785651,
      null, '2026-08-12 15:46:15', 'synced', '2026-08-15T06:05:45.811Z', 3, 1786581106358.2515, 1786774065811],
  );
  db.run(
    `INSERT INTO sync_config (root_id, local_path, cursor, status, updated_at, user_id, last_sync_at, sync_version)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    ['2083478593059856385', 'E:\\sync', 8, 'active', '2026-08-15T06:05:45.813Z', null, 1786773945813, 1],
  );

  const rebuilt = ensureIdColumnsText(db);
  assert.deepEqual(rebuilt.sort(), ['sync_config', 'sync_state']);

  const stmt = db.prepare('SELECT root_id, local_path, node_id, fail_count, fail_mtime, next_retry_at FROM sync_state');
  stmt.step();
  const s = stmt.getAsObject() as Record<string, unknown>;
  stmt.free();
  assert.equal(s.root_id, '2083478593059856385');
  assert.equal(s.node_id, '2087445337642287105');
  assert.equal(s.fail_count, 3);
  assert.equal(s.fail_mtime, 1786581106358.2515);
  assert.equal(s.next_retry_at, 1786774065811);

  const c = db.prepare('SELECT root_id, cursor, last_sync_at, sync_version FROM sync_config');
  c.step();
  const cfg = c.getAsObject() as Record<string, unknown>;
  c.free();
  assert.equal(cfg.root_id, '2083478593059856385');
  assert.equal(cfg.cursor, 8);
  assert.equal(cfg.last_sync_at, 1786773945813);
  // 重建后 sync_version 列与值必须保留（防 schema 常量漏列导致升级后版本门控失效）
  assert.equal(cfg.sync_version, 1);
});

test('ensureIdColumnsText 幂等：二次调用不再重建', async () => {
  const db = await createOldSchemaDb();
  db.run("INSERT INTO sync_state (root_id, local_path, node_id, updated_at) VALUES ('1', '/a.txt', '1', 'x')");
  db.run("INSERT INTO sync_config (root_id, local_path, updated_at) VALUES ('1', 'E:\\sync', 'x')");
  assert.equal(ensureIdColumnsText(db).length, 2);
  assert.equal(ensureIdColumnsText(db).length, 0);
});

test('ensureIdColumnsText 对新库（已是 TEXT）不重建', async () => {
  const SQL = await getSQL();
  const db = new SQL.Database();
  db.run('CREATE TABLE sync_state (local_path TEXT PRIMARY KEY, node_id TEXT)');
  db.run('CREATE TABLE sync_config (root_id TEXT PRIMARY KEY, local_path TEXT)');
  assert.deepEqual(ensureIdColumnsText(db), []);
});
