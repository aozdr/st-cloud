// 隔离执行 CR-04/ID 的关键分支；只转译并调用当前源码，所有 FS/API/DB 均为内存替身。
// 运行：node .ai/docs/20260924-code-review-testcases/execution-20260925/desktop-probes.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const ts = require(path.resolve(__dirname, '../../../../st-desktop/node_modules/typescript'));

const repo = path.resolve(__dirname, '../../../../');
let passed = 0;
let attempted = 0;

function load(source, mocks) {
  const exports = {};
  const code = ts.transpileModule(fs.readFileSync(path.join(repo, source), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  vm.runInNewContext(code, {
    exports, require: name => mocks[name] || {}, console: { ...console, error() {} },
    process, Buffer, setTimeout, setInterval, clearInterval, clearTimeout, queueMicrotask,
  }, { filename: source });
  return exports;
}

async function check(id, title, action) {
  attempted++;
  try {
    await action();
    passed++;
    console.log(`PASS ${id} ${title}`);
  } catch (error) {
    console.error(`FAIL ${id} ${title}:`, error);
    process.exitCode = 1;
  }
}

function movingHarness({ changed = false, missing = false, occupant = false } = {}) {
  const present = new Set(['C:/local']);
  if (!missing) present.add('C:/local/old');
  const states = new Map();
  if (!missing) states.set('/old', { nodeId: occupant ? 'X' : 'N', localPath: '/old', localMtime: 1 });
  const moves = [];
  let folderCalls = 0;
  let reconciles = 0;
  const fakeFs = {
    existsSync: location => present.has(location),
    statSync: () => ({ mtimeMs: 1, isDirectory: () => true }),
    mkdirSync: location => present.add(location),
    renameSync: (source, target) => { moves.push([source, target]); present.delete(source); present.add(target); },
  };
  const database = {
    getSyncState: (_root, rel) => states.get(rel),
    getAllSyncStates: () => [...states.values()],
    deleteSyncState: (_root, rel) => states.delete(rel),
    upsertSyncState: row => states.set(row.localPath, { ...states.get(row.localPath), ...row }),
  };
  const mocks = {
    fs: fakeFs, path: path.posix, crypto: require('node:crypto'), './database': database,
    './sync-utils': { isLocallyChanged: () => changed },
    './sync/sync-shared': { syncLog() {} },
    './sync/sync-recovery': { preserveAndRemove: (_root, _id, _rootPath, source) => { present.delete(source); return 'recovery'; } },
    './sync/sync-reconcile': {
      fullReconcile: async () => { reconciles++; return true; },
      reconcileFolder: async () => { if (++folderCalls === 1) throw Error('network failed'); },
    },
  };
  const Engine = load('st-desktop/src/sync-engine.ts', mocks).SyncEngine;
  const engine = Object.create(Engine.prototype);
  Object.assign(engine, {
    root: { rootId: 'R', cloudFolderNodeId: 'root', localPath: 'C:/local' },
    readCloudNode: async id => ({ path: id === 'root' ? '/root' : '/root/new', status: 0 }),
    absPathFor: rel => 'C:/local' + rel,
    isExcluded: () => false,
    markEngineWritten() {},
    isSelfWrite: () => false,
  });
  const item = { changeType: 'MOVE', nodeId: 'N', oldPath: '/old', path: '/new',
    nodeType: changed ? 0 : 1, logId: '9007199254740993' };
  return { engine, item, states, moves, get folderCalls() { return folderCalls; }, get reconciles() { return reconciles; } };
}

function deltaHarness({ conflict = false, race = false } = {}) {
  let cursor = '0';
  let cloudMd5 = 'v2';
  let streamMd5 = race ? 'v3' : 'v2';
  let failUpload = conflict;
  let failStream = conflict;
  let downloads = 0;
  let conflicts = 0;
  const userId = '9223372036854775806';
  const states = new Map([['/a', { rootId: 'R', localPath: '/a', nodeId: userId, md5: 'old', localMtime: 1 }]]);
  const fakeFs = {
    existsSync: () => true,
    statSync: () => ({ size: 4, mtimeMs: conflict ? 2 : 1 }),
    mkdirSync() {}, unlinkSync() {}, renameSync() {}, rmSync() {}, copyFileSync() {},
    mkdtempSync: () => '/tmp/conflict',
    createWriteStream: () => new EventEmitter(),
  };
  const database = {
    getSyncState: (_root, rel) => states.get(rel),
    upsertSyncState: row => states.set(row.localPath, { ...states.get(row.localPath), ...row }),
    insertSyncHistory() {},
    getSyncConfig: () => ({ cursor }),
    upsertSyncConfig: config => { cursor = config.cursor; },
  };
  const shared = { syncLog(kind, message) { if (kind === 'error') console.log('PROBE ERROR:', message); },
    emitSyncEvent() {}, withRetry: action => action() };
  const api = { get: async () => {
    if (failStream) throw Error('offline');
    return { data: { pipe: writer => queueMicrotask(() => writer.emit('finish')), on() {} } };
  } };
  const downloader = load('st-desktop/src/sync/sync-download.ts', {
    fs: fakeFs, path: path.posix, os: { tmpdir: () => '/tmp' },
    crypto: { randomUUID: () => 'fixed' },
    '../utils/md5': { calculateFileMd5: async () => streamMd5 },
    '../api-client': { apiClient: api }, '../database': database,
    './sync-shared': shared,
    '../sync-utils': { uniqueConflictName: name => name + '-copy', conflictRelPath: () => '/a-copy' },
  });
  const Engine = load('st-desktop/src/sync-engine.ts', {
    fs: fakeFs, path: path.posix, './database': database, './sync/sync-shared': shared,
  }).SyncEngine;
  const engine = Object.create(Engine.prototype);
  Object.assign(engine, {
    root: { rootId: 'R', cloudFolderNodeId: 'root', localPath: '/local' },
    syncing: false,
    readCloudNode: async id => ({ path: id === 'root' ? '/root' : '/root/a', status: 0,
      size: 4, md5: cloudMd5, updatedAt: '2026-09-25 12:00:00' }),
    absPathFor: rel => '/local' + rel,
    isExcluded: () => false,
    markEngineWritten() {},
    isSelfWrite: () => false,
    scanLocalChanges: async () => {},
    uploadFile: async () => { if (failUpload) throw Error('offline'); },
  });
  engine.downloadFile = (...args) => { downloads++; return downloader.downloadFile(engine, ...args); };
  engine.handleConflict = (...args) => { conflicts++; return downloader.handleConflict(engine, ...args); };
  const event = { nodeId: userId, path: '/a', nodeType: 1, changeType: 'UPDATE' };
  engine.fetchDelta = async () => ({ scopeProjectionVersion: 2, cursor: '2', hasMore: false,
    changes: [{ ...event, size: 3, md5: 'v1' }, { ...event, size: 4, md5: 'v2' }] });
  return {
    engine, states, get cursor() { return cursor; }, get downloads() { return downloads; },
    get conflicts() { return conflicts; },
    setCurrent(md5) { cloudMd5 = md5; streamMd5 = md5; },
    recover() { failStream = false; failUpload = false; },
  };
}

(async () => {
  await check('TC04-15', 'MOVE 旧路径属于其他节点时不误移动', async () => {
    const h = movingHarness({ occupant: true });
    await h.engine.processCloudDelta([h.item]);
    assert.equal(h.moves.length, 0);
    assert.equal(h.states.get('/old').nodeId, 'X');
    assert.equal(h.reconciles, 1);
  });
  await check('TC04-16', 'MOVE 旧路径身份未知时不直接移动', async () => {
    const h = movingHarness();
    h.states.clear();
    await h.engine.processCloudDelta([h.item]);
    assert.equal(h.moves.length, 0);
    assert.equal(h.reconciles, 1);
  });
  for (const [id, missing] of [['TC04-17', false], ['TC04-18', true]]) {
    await check(id, '目录补齐失败后用原状态续传', async () => {
      const h = movingHarness({ changed: true, missing });
      await assert.rejects(h.engine.processCloudDelta([h.item]), /network failed/);
      assert.equal(h.states.get('/new').status, 'pending');
      await h.engine.processCloudDelta([h.item]);
      assert.equal(h.states.get('/new').status, 'synced');
      assert.equal(h.folderCalls, 2);
    });
  }
  await check('TC04-21', '字符串文件长度安全解析', async () => {
    const shared = load('st-desktop/src/sync/sync-shared.ts', { electron: { BrowserWindow: {} } });
    assert.equal(shared.parseFileSize('3'), 3);
    assert.equal(shared.parseFileSize('0'), 0);
    assert.equal(shared.parseFileSize(null), null);
    assert.throws(() => shared.parseFileSize('9007199254740993'));
  });
  await check('TC04-22', '历史 V1/V2 日志只下载当前 V2', async () => {
    const h = deltaHarness();
    await h.engine.syncOnce();
    assert.equal(h.cursor, '2');
    assert.equal(h.downloads, 1);
    assert.equal(h.states.get('/a').md5, 'v2');
  });
  await check('TC04-23', '下载前内容变化拒绝写入并重试', async () => {
    const h = deltaHarness({ race: true });
    await h.engine.syncOnce();
    assert.equal(h.cursor, '0');
    assert.equal(h.states.get('/a').md5, 'old');
    h.setCurrent('v3');
    await h.engine.syncOnce();
    assert.equal(h.cursor, '2');
    assert.equal(h.states.get('/a').md5, 'v3');
  });
  await check('TC04-26', '冲突两轮失败后基线保持并能继续重试', async () => {
    const h = deltaHarness({ conflict: true });
    await h.engine.syncOnce();
    await h.engine.syncOnce();
    assert.equal(h.cursor, '0');
    assert.equal(h.states.get('/a').md5, 'old');
    assert.equal(h.conflicts, 2);
    h.recover();
    await h.engine.syncOnce();
    assert.equal(h.cursor, '2');
    assert.equal(h.conflicts, 3);
  });
  await check('TCDB-08', 'JWT 裸数字和字符串 userId 均无精度丢失', async () => {
    const client = load('st-desktop/src/api-client.ts', {
      axios: { create: () => ({ interceptors: { request: { use() {} }, response: { use() {} } } }) },
      './server-config': { getServerUrl: () => 'http://localhost' },
    });
    const userId = '9223372036854775806';
    for (const json of [`{"userId":${userId}}`, `{"userId":"${userId}"}`]) {
      client.setAuth('head.' + Buffer.from(json).toString('base64url') + '.signature', 'refresh');
      assert.equal(client.getUserId(), userId);
    }
    client.setAuth('head.' + Buffer.from('{"otherId":"1"}').toString('base64url') + '.signature', 'refresh');
    assert.equal(client.getUserId(), null);
    client.setAuth('malformed', 'refresh');
    assert.equal(client.getUserId(), null);
  });
  await check('TC04-30', '源文件在核验后变化不能被旧备份覆盖或删除', async () => {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-cr04-preserve-'));
    try {
      const root = path.join(temporary, 'root');
      const userData = path.join(temporary, 'userData');
      fs.mkdirSync(root);
      fs.mkdirSync(userData);
      const source = path.join(root, 'a.txt');
      fs.writeFileSync(source, 'original-v1');
      const recovery = load('st-desktop/src/sync/sync-recovery.ts', {
        fs, path, crypto: require('node:crypto'), electron: { app: { getPath: () => userData } },
        '../database': { getAllSyncConfigs: () => [] },
      });
      const output = recovery.preserveAndRemove('R', 'OP', root, source, '/a.txt', () => {
        fs.writeFileSync(source, 'user-edited-v2');
      });
      const saved = fs.readFileSync(path.join(output, 'files', 'a.txt'), 'utf8');
      assert.equal(saved, 'user-edited-v2', '用户的新版本未出现在恢复副本中');
    } finally {
      if (path.dirname(temporary) !== path.resolve(os.tmpdir())) throw Error('临时目录越界，拒绝清理');
      fs.rmSync(temporary, { recursive: true, force: true });
    }
  });
  console.log(`Summary: ${passed}/${attempted} isolated probes passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
