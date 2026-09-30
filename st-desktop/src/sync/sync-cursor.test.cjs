const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const code = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '../sync-engine.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
}).outputText;

function harness(payload, externalDatabase, ids = {}) {
  const exports = {};
  const observed = [];
  const persisted = [];
  const rootId = ids.rootId || '9007199254740993';
  let config = { rootId, localPath: '/isolated', cursor: ids.cursor || '9007199254740993', status: 'active' };
  const mocks = {
    './api-client': { apiClient: { get: async (url, config) => {
      observed.push({ url, since: config.params.since });
      return { data: { data: typeof payload === 'function' ? payload(config.params.since) : payload } };
    } } },
    './database': externalDatabase || {
      getSyncConfig: () => config,
      upsertSyncConfig: row => { persisted.push(row); config = { ...config, ...row }; },
    },
    './sync/sync-shared': {
      parseFileSize: size => size, withRetry: fn => fn(), syncLog() {}, emitSyncEvent() {}, SYNC_ENGINE_VERSION: 4,
    },
  };
  vm.runInNewContext(code, { exports, Buffer, console, require: name => mocks[name] || {} });
  const engine = Object.create(exports.SyncEngine.prototype);
  engine.root = { rootId, localPath: '/isolated' };
  engine.processCloudDelta = async () => {};
  engine.scanLocalChanges = async () => {};
  return { engine, observed, persisted, current: () => externalDatabase
    ? externalDatabase.getSyncConfig('9007199254740993') : config };
}

test('TCDB-07: 三组root/node/cursor逐字贯穿实际引擎请求与delta消费', async () => {
  const ids=['9007199254740993','9007199254740995','9223372036854775806'];
  for(let i=0;i<ids.length;i++) {
    const rootId=ids[i],nodeId=ids[(i+1)%3],cursor=ids[(i+2)%3];
    const h=harness({cursor,changes:[{nodeId,rootId,operation:'CREATE'}],hasMore:false,scopeProjectionVersion:2},null,{rootId,cursor});
    const consumed=[];
    h.engine.processCloudDelta=async changes => consumed.push(...changes);
    await h.engine.syncOnce();
    assert.equal(h.observed[0].url,`/sync/roots/${rootId}/delta`);
    assert.equal(h.observed[0].since,cursor);
    assert.equal(consumed[0].nodeId,nodeId);
    assert.equal(consumed[0].rootId,rootId);
    assert.equal(h.current().cursor,cursor);
    assert.equal(h.current().rootId,rootId);
  }
});

function syncMeta(database, save) {
  const exports = {};
  const source = fs.readFileSync(path.resolve(__dirname, '../db/sync-meta.ts'), 'utf8');
  const transpiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  vm.runInNewContext(transpiled, { exports, Date, require: name => {
    if (name !== './db-core') throw Error('Unexpected dependency ' + name);
    return { getDb: () => database, persist: () => save(Buffer.from(database.export())) };
  } });
  return exports;
}

test('TCDB-10: 大游标逐字符传递，不经过 Number', async () => {
  const cursor = '9223372036854775806';
  const h = harness({ cursor, changes: [], hasMore: false, scopeProjectionVersion: 2 });
  const result = await h.engine.fetchDelta('9007199254740993');
  assert.equal(result.cursor, cursor);
  assert.equal(h.observed[0].since, '9007199254740993');
  assert.ok(h.observed[0].url.includes('/9007199254740993/'));
});

test('TCDB-10: 相邻大游标连续分页并持久化最后一页', async () => {
  const first = '9007199254740994';
  const last = '9007199254740995';
  const h = harness(since => since === '9007199254740993'
    ? { cursor: first, changes: [], hasMore: true, scopeProjectionVersion: 2 }
    : { cursor: last, changes: [], hasMore: false, scopeProjectionVersion: 2 });
  await h.engine.syncOnce();
  assert.deepEqual(h.observed.map(x => x.since), ['9007199254740993', first]);
  assert.equal(h.current().cursor, last);
  assert.ok(h.persisted.some(row => row.cursor === first));
});

test('TCDB-10: hasMore=true 且大游标未前进不确认页面', async () => {
  const h = harness({ cursor: '9007199254740993', changes: [], hasMore: true, scopeProjectionVersion: 2 });
  await h.engine.syncOnce();
  assert.equal(h.persisted.length, 0);
  assert.equal(h.current().cursor, '9007199254740993');
});

test('TC04-08: 协议版本缺失或错误时游标不推进', async () => {
  for (const version of [undefined, 1, '2']) {
    const h = harness({ cursor: '9007199254740994', changes: [], hasMore: false,
      scopeProjectionVersion: version });
    await h.engine.syncOnce();
    assert.equal(h.persisted.length, 0);
    assert.equal(h.current().cursor, '9007199254740993');
  }
});

test('TC04-08: 数字游标和损坏响应均被拒绝', async () => {
  const valid = { cursor: '1', changes: [], hasMore: false, scopeProjectionVersion: 2 };
  for (const broken of [
    { ...valid, cursor: 9223372036854775806 },
    { ...valid, cursor: 1 },
    { ...valid, cursor: undefined },
    { ...valid, cursor: '-1' },
    { ...valid, cursor: '01' },
    { ...valid, cursor: '1.5' },
    { ...valid, changes: null },
    { ...valid, hasMore: 'false' },
  ]) {
    const h = harness(broken);
    await assert.rejects(() => h.engine.fetchDelta('0'), /同步增量响应格式无效/);
  }
});

test('TCDB-10: 真实 SQLite 持久化大游标，重启后续页并拒绝停滞', async () => {
  const initSqlJs = require(path.resolve(__dirname, '../../node_modules/sql.js'));
  const SQL = await initSqlJs({ locateFile: () => path.resolve(__dirname,
    '../../node_modules/sql.js/dist/sql-wasm.wasm') });
  let database = new SQL.Database();
  database.run(`CREATE TABLE sync_config (
    root_id TEXT PRIMARY KEY, local_path TEXT NOT NULL, cursor TEXT DEFAULT '0',
    status TEXT DEFAULT 'active', user_id TEXT, last_sync_at INTEGER,
    sync_version INTEGER, updated_at TEXT NOT NULL)`);
  let saved;
  let meta = syncMeta(database, bytes => { saved = bytes; });
  const rootId = '9007199254740993';
  meta.upsertSyncConfig({ rootId, localPath: '/isolated', cursor: rootId });
  const first = harness({ cursor: '9007199254740994', changes: [],
    hasMore: false, scopeProjectionVersion: 2 }, meta);
  await first.engine.syncOnce();
  assert.equal(first.current().cursor, '9007199254740994');
  database.close();

  database = new SQL.Database(new Uint8Array(saved));
  meta = syncMeta(database, bytes => { saved = bytes; });
  assert.equal(meta.getSyncConfig(rootId).cursor, '9007199254740994');
  const restarted = harness({ cursor: '9007199254740995', changes: [],
    hasMore: false, scopeProjectionVersion: 2 }, meta);
  await restarted.engine.syncOnce();
  assert.equal(restarted.observed[0].since, '9007199254740994');
  assert.equal(meta.getSyncConfig(rootId).cursor, '9007199254740995');
  const stagnant = harness({ cursor: '9007199254740995', changes: [],
    hasMore: true, scopeProjectionVersion: 2 }, meta);
  await stagnant.engine.syncOnce();
  assert.equal(meta.getSyncConfig(rootId).cursor, '9007199254740995');
  database.close();
});

test('TC04-06: 空过滤页仍继续读取下一页的有效事件', async () => {
  const received = [];
  const h = harness(since => since === '9007199254740993'
    ? { cursor: '9007199254740994', changes: [], hasMore: true, scopeProjectionVersion: 2 }
    : { cursor: '9007199254740995', changes: [{ id: 'event-2' }],
      hasMore: false, scopeProjectionVersion: 2 });
  h.engine.processCloudDelta = async changes => received.push(...changes.map(change => change.id));
  await h.engine.syncOnce();
  assert.deepEqual(h.observed.map(request => request.since),
    ['9007199254740993', '9007199254740994']);
  assert.deepEqual(received, ['event-2']);
  assert.equal(h.current().cursor, '9007199254740995');
});

test('TC04-34: 第二页失败保留第一页游标，恢复后从第二页继续', async () => {
  const initSqlJs = require(path.resolve(__dirname, '../../node_modules/sql.js'));
  const SQL = await initSqlJs({ locateFile: () => path.resolve(__dirname,
    '../../node_modules/sql.js/dist/sql-wasm.wasm') });
  let database = new SQL.Database();
  database.run(`CREATE TABLE sync_config (
    root_id TEXT PRIMARY KEY, local_path TEXT NOT NULL, cursor TEXT DEFAULT '0',
    status TEXT DEFAULT 'active', user_id TEXT, last_sync_at INTEGER,
    sync_version INTEGER, updated_at TEXT NOT NULL)`);
  let saved;
  let meta = syncMeta(database, bytes => { saved = bytes; });
  meta.upsertSyncConfig({ rootId: '9007199254740993', localPath: '/isolated',
    cursor: '9007199254740993' });
  let failSecondPage = true;
  const payload = since => {
    if (since === '9007199254740993') return {
      cursor: '9007199254740994', changes: [], hasMore: true, scopeProjectionVersion: 2,
    };
    if (failSecondPage) throw Error('isolated second page network failure');
    return { cursor: '9007199254740995', changes: [], hasMore: false, scopeProjectionVersion: 2 };
  };
  const h = harness(payload, meta);
  await h.engine.syncOnce();
  assert.equal(h.current().cursor, '9007199254740994');
  assert.deepEqual(h.observed.map(request => request.since),
    ['9007199254740993', '9007199254740994']);
  database.close();
  database = new SQL.Database(new Uint8Array(saved));
  meta = syncMeta(database, bytes => { saved = bytes; });
  assert.equal(meta.getSyncConfig('9007199254740993').cursor, '9007199254740994');
  failSecondPage = false;
  const resumed = harness(payload, meta);
  await resumed.engine.syncOnce();
  assert.equal(meta.getSyncConfig('9007199254740993').cursor, '9007199254740995');
  assert.deepEqual(resumed.observed.map(request => request.since), ['9007199254740994']);
  database.close();
});
