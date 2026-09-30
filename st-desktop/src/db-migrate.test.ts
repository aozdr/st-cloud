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
  assert.equal(cfg.cursor, '8');
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
  db.run('CREATE TABLE sync_config (root_id TEXT PRIMARY KEY, local_path TEXT, cursor TEXT)');
  assert.deepEqual(ensureIdColumnsText(db), []);
});

test('TCDB-09 旧库大 root/node/cursor 以 SQL 整数字面量迁移并隔离两根', async () => {
  const db = await createOldSchemaDb();
  db.run(`INSERT INTO sync_config (root_id, local_path, cursor, user_id, updated_at)
    VALUES (9007199254740993, '/root-a', 9223372036854775806, '9007199254740997', 'now'),
           (9007199254740995, '/root-b', 9223372036854775805, '9007199254740999', 'now')`);
  db.run(`INSERT INTO sync_state (root_id, local_path, node_id, updated_at)
    VALUES ('9007199254740993', '/a.txt', 9007199254740997, 'now'),
           ('9007199254740995', '/b.txt', 9007199254740999, 'now')`);
  assert.deepEqual(ensureIdColumnsText(db).sort(), ['sync_config', 'sync_state']);
  const config = db.exec('SELECT root_id, cursor, user_id FROM sync_config ORDER BY root_id')[0].values;
  assert.deepEqual(config, [
    ['9007199254740993', '9223372036854775806', '9007199254740997'],
    ['9007199254740995', '9223372036854775805', '9007199254740999'],
  ]);
  const state = db.exec('SELECT root_id, local_path, node_id FROM sync_state ORDER BY root_id')[0].values;
  assert.deepEqual(state, [
    ['9007199254740993', '/a.txt', '9007199254740997'],
    ['9007199254740995', '/b.txt', '9007199254740999'],
  ]);
  assert.equal(ensureIdColumnsText(db).length, 0);
  db.close();
});

test('TCDB-09 全部现有迁移列、主键索引与落盘重开保持，非ID字段不转换', async () => {
  const SQL = await getSQL();
  const db = await createOldSchemaDb();
  db.run(`INSERT INTO sync_config VALUES (9007199254740993,'/a',9223372036854775806,'paused','cfg-time','9223372036854775806',123456,4)`);
  db.run(`INSERT INTO sync_state VALUES ('9007199254740993','/same',9007199254740995,'hash',123,456,'cloud-time','needs_review','state-time',3,789.25,987)`);
  ensureIdColumnsText(db);
  // 实际迁移目标只有sync_state/root,node和sync_config/root,cursor；user_id原已为TEXT，role列不存在。
  assert.deepEqual(db.exec('SELECT * FROM sync_config')[0].values, [['9007199254740993','/a','9223372036854775806','paused','9223372036854775806',123456,4,'cfg-time']]);
  assert.deepEqual(db.exec('SELECT * FROM sync_state')[0].values, [['9007199254740993','/same','9007199254740995','hash',123,456,'cloud-time','needs_review',3,789.25,987,'state-time']]);
  db.run(`INSERT INTO sync_state(root_id,local_path,node_id,updated_at) VALUES ('9007199254740995','/same','9223372036854775806','b')`);
  assert.throws(() => db.run(`INSERT INTO sync_state(root_id,local_path,updated_at) VALUES ('9007199254740993','/same','duplicate')`));
  const snapshot = db.exec("SELECT name,type,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name");
  const reopened = new SQL.Database(db.export());
  assert.deepEqual(ensureIdColumnsText(reopened), []);
  assert.deepEqual(reopened.exec("SELECT name,type,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name"), snapshot);
  assert.deepEqual(reopened.exec('SELECT * FROM sync_state ORDER BY root_id'), db.exec('SELECT * FROM sync_state ORDER BY root_id'));
  for (const [table, columns] of [['sync_state', ['root_id','node_id']], ['sync_config',['root_id','cursor','user_id']]] as const) {
    const info = reopened.exec(`PRAGMA table_info(${table})`)[0].values;
    for (const column of columns) assert.equal(info.find(row=>row[1]===column)?.[2], 'TEXT');
  }
  reopened.close();db.close();
});
