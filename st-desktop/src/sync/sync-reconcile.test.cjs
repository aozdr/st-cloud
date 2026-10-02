const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, 'sync-reconcile.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
}).outputText;

function fixture(t) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-reconcile-test-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const root = path.join(temporary, 'root');
  fs.mkdirSync(root);
  fs.writeFileSync(path.join(root, 'old.txt'), 'old-original');
  const states = new Map([['/old.txt', { localPath: '/old.txt', nodeId: 'old', md5: 'old-md5' }]]);
  const deleted = [];
  const requestedPages = [];
  const logs = [];
  const cursorWrites = [];
  let cursor = '1';
  let listResponse = page => ({ code: 200, data: { records: [], pages: 1 } });
  const apiClient = { get: async (url, config) => {
    if (url === '/file/list') {
      requestedPages.push(config.params.page);
      const response = listResponse(config.params.page);
      if (response instanceof Error) throw response;
      return { data: response };
    }
    if (url === '/sync/roots/root-1/delta') return { data: { data: {
      cursor: '2', hasMore: false, scopeProjectionVersion: 2, reconcileRequired: true, changes: [],
    } } };
    if (url === '/file/root') return { data: { code: 200, data: { path: '/cloud/root' } } };
    if (url === '/file/old') return { data: { code: 200, data: { path: '/cloud/root/old.txt' } } };
    throw Error('Unexpected URL ' + url);
  } };
  const dependencies = {
    fs, path, crypto: require('node:crypto'), '../api-client': { apiClient },
    '../database': {
      getSyncState: (_root, rel) => states.get(rel),
      getAllSyncStates: () => [...states.values()],
      deleteSyncState: (_root, rel) => { deleted.push(rel); states.delete(rel); },
      upsertSyncState: state => states.set(state.localPath, state),
      getSyncConfig: () => ({ rootId: 'root-1', localPath: root, cursor, status: 'active' }),
      upsertSyncConfig: row => { cursorWrites.push(row); cursor = row.cursor; },
    },
    '../utils/md5': { calculateFileMd5: async () => 'old-md5' },
    './sync-shared': { withRetry: fn => fn(), syncLog: (type, message) => logs.push({ type, message }), parseFileSize: size => size },
    './sync-recovery': { preserveAndRemove: () => { throw Error('unexpected removal'); } },
  };
  const exports = {};
  vm.runInNewContext(code, { exports, require(name) {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
    return dependencies[name];
  } });
  const downloads = [];
  const ctx = {
    root: { rootId: 'root-1', localPath: root, cloudFolderNodeId: 'root' },
    isExcluded: () => false,
    absPathFor: rel => path.join(root, ...rel.split('/').filter(Boolean)),
    async downloadFile(item, absolute, rel) {
      downloads.push(rel);
      fs.writeFileSync(absolute, 'new-cloud');
      states.set(rel, { localPath: rel, nodeId: item.nodeId, md5: item.md5 });
    },
  };
  const engineCode = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../sync-engine.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  const engineExports = {};
  const engineDependencies = {
    fs, path, crypto: require('node:crypto'), './api-client': { apiClient },
    './database': dependencies['../database'],
    './file-watcher': {}, './utils/md5': {}, './sync-retry': {}, './sync-utils': {},
    './sync/sync-shared': { ...dependencies['./sync-shared'], emitSyncEvent() {}, SYNC_ENGINE_VERSION: 4 },
    './sync/sync-upload': {}, './sync/sync-download': {},
    './sync/sync-reconcile': { fullReconcile: () => exports.fullReconcile(ctx) },
    './sync/sync-recovery': {},
  };
  vm.runInNewContext(engineCode, { exports: engineExports, console, require(name) {
    if (!(name in engineDependencies)) throw Error('Unexpected engine dependency ' + name);
    return engineDependencies[name];
  } });
  const engine = Object.create(engineExports.SyncEngine.prototype);
  engine.root = ctx.root;
  engine.processCloudDelta = async () => {};
  engine.scanLocalChanges = async () => {};
  return { root, states, deleted, requestedPages, downloads, logs, ctx, cursorWrites, dependencies,
    setCursor: value => { cursor = value; },
    currentCursor: () => cursor, runWithCursor: () => engine.syncOnce(),
    respond: fn => { listResponse = fn; }, run: () => exports.fullReconcile(ctx) };
}

test('TC04-33: 不完整列举和第二页故障不清理原文件或状态，恢复后补齐', async t => {
  const f = fixture(t);
  const failures = [
    [() => ({ code: 500, message: 'server error' }), /云端目录列举失败/],
    [() => ({ code: 200, data: { pages: 1 } }), /云端目录响应不完整/],
    [() => ({ code: 200, data: { records: [], pages: 'invalid' } }), /云端目录响应不完整/],
    [() => ({ code: 200, data: { records: [], pages: -1 } }), /云端目录响应不完整/],
    [() => ({ code: 200, data: { records: [], pages: null } }), /云端目录响应不完整/],
    [() => ({ code: 200, data: { records: [], pages: 1.5 } }), /云端目录响应不完整/],
    ...['', ' ', '01', '1.5', '-1', '9007199254740992', Number.MAX_SAFE_INTEGER + 1].map(pages =>
      [() => ({ code: 200, data: { records: [], pages } }), /云端目录响应不完整/]),
    [page => page === 1 ? { code: 200, data: { records: [], pages: 2 } }
      : { code: 200, data: { records: [], pages: 1 } }, /云端目录响应不完整/],
    [page => page === 1 ? { code: 200, data: { records: [], pages: 2 } }
      : { code: 200, data: { pages: 2 } }, /云端目录响应不完整/],
    [page => page === 1 ? { code: 200, data: { records: [], pages: 2 } } : Error('page 2 offline'), /page 2 offline/],
  ];
  for (const [response, expected] of failures) {
    f.logs.length = 0;
    f.respond(response);
    assert.equal(await f.run(), false);
    assert.match(f.logs.at(-1).message, expected);
    assert.equal(fs.readFileSync(path.join(f.root, 'old.txt'), 'utf8'), 'old-original');
    assert.equal(f.states.get('/old.txt').nodeId, 'old');
    assert.deepEqual(f.deleted, []);
  }
  assert.ok(f.requestedPages.includes(2));
  f.respond(page => page === 1 ? { code: 200, data: { records: [], pages: 2 } }
    : Error('page 2 offline'));
  await f.runWithCursor();
  assert.equal(f.currentCursor(), '1');
  assert.deepEqual(f.cursorWrites, []);
  f.respond(() => ({ code: 200, data: { records: [], pages: 0 } }));
  // 空目录的 0 页合法，但历史节点仍在根内；旧状态不得被当作已删除而清理。
  assert.equal(await f.run(), false);
  assert.equal(f.states.get('/old.txt').nodeId, 'old');
  f.respond(page => page === 1 ? { code: 200, data: { records: [], pages: 2 } } : {
    code: 200, data: { pages: 2, records: [
      { id: 'old', parentId: 'root', nodeType: 1, name: 'old.txt', fileSize: 12,
        fileMd5: 'old-md5', updatedAt: '2020-01-01T00:00:00Z' },
      { id: 'new', parentId: 'root', nodeType: 1, name: 'new.txt', fileSize: 9,
        fileMd5: 'new-md5', updatedAt: '2020-01-01T00:00:00Z' },
    ] },
  });
  assert.equal(await f.run(), true);
  assert.equal(fs.readFileSync(path.join(f.root, 'old.txt'), 'utf8'), 'old-original');
  assert.equal(fs.readFileSync(path.join(f.root, 'new.txt'), 'utf8'), 'new-cloud');
  assert.deepEqual(f.downloads, ['/new.txt']);
  assert.deepEqual(f.deleted, []);
  await f.runWithCursor();
  assert.equal(f.currentCursor(), '2');
  assert.ok(f.cursorWrites.some(row => row.cursor === '2'));
});

test('空目录 pages=0 且无旧状态可以完成对账', async t => {
  const f = fixture(t);
  f.states.clear();
  f.respond(() => ({ code: 200, data: { records: [], pages: 0 } }));
  assert.equal(await f.run(), true);
  assert.deepEqual(f.deleted, []);
});

test('后端Long字符串分页保持多页下载，不能把第二页漏当空目录', async t => {
  const f = fixture(t);
  f.respond(page => ({ code: 200, data: { pages: '2', records: page === 1 ? [] : [
    { id: 'old', parentId: 'root', nodeType: 1, name: 'old.txt', fileSize: 12,
      fileMd5: 'old-md5', updatedAt: '2020-01-01T00:00:00Z' },
    { id: 'new', parentId: 'root', nodeType: 1, name: 'new.txt', fileSize: 9,
      fileMd5: 'new-md5', updatedAt: '2020-01-01T00:00:00Z' },
  ] } }));
  assert.equal(await f.run(), true);
  assert.deepEqual(f.requestedPages, [1, 2]);
  assert.equal(fs.readFileSync(path.join(f.root, 'new.txt'), 'utf8'), 'new-cloud');
  assert.equal(f.states.get('/old.txt').nodeId, 'old');
});

test('根映射和同节点重复斜杠历史映射可原地恢复，保持原字节与规范映射', async t => {
  const f = fixture(t);
  f.states.set('/', { localPath: '/', nodeId: 'root', status: 'synced' });
  f.states.set('//old.txt', { localPath: '//old.txt', nodeId: 'old', md5: 'old-md5' });
  f.respond(() => ({ code: 200, data: { pages: '1', records: [
    { id: 'old', parentId: 'root', nodeType: 1, name: 'old.txt', fileSize: 12,
      fileMd5: 'old-md5', updatedAt: '2020-01-01T00:00:00Z' },
  ] } }));
  assert.equal(await f.run(), true);
  assert.equal(f.states.has('//old.txt'), false);
  assert.equal(f.states.get('/old.txt').nodeId, 'old');
  assert.equal(f.states.get('/').nodeId, 'root');
  assert.equal(fs.readFileSync(path.join(f.root, 'old.txt'), 'utf8'), 'old-original');
  assert.deepEqual(f.downloads, []);
});

function realRecovery(f) {
  const userData = path.join(path.dirname(f.root), 'userData');
  fs.mkdirSync(userData);
  const recoveryCode = ts.transpileModule(fs.readFileSync(path.join(__dirname, 'sync-recovery.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  const recovery = {};
  vm.runInNewContext(recoveryCode, { exports: recovery, require: name => ({ fs, path,
    electron: { app: { getPath: () => userData } },
    '../database': { getAllSyncConfigs: () => [] },
  })[name] });
  Object.assign(f.dependencies['./sync-recovery'], recovery);
  f.ctx.markEngineWritten = () => {};
  return { recovery, userData };
}

test('同节点重复往返移动独立保全各轮原件，保留旧固定ID副本', async t => {
  const f = fixture(t);
  const { recovery, userData } = realRecovery(f);
  const fixed = recovery.preserveAndRemove('root-1', 'legacy-old', f.root,
    path.join(f.root, 'old.txt'), '/old.txt', () => {});
  fs.writeFileSync(path.join(f.root, 'old.txt'), 'current-original');
  for (const [cursor, name] of [['10', 'new.txt'], ['11', 'old.txt'], ['12', 'new.txt'], ['13', 'old.txt']]) {
    f.setCursor(cursor);
    f.respond(() => ({ code: 200, data: { pages: '1', records: [{ id: 'old', parentId: 'root',
      nodeType: 1, name, fileSize: 9, fileMd5: 'new-md5', updatedAt: '2020-01-01T00:00:00Z' }] } }));
    assert.equal(await f.run(), true, JSON.stringify(f.logs));
    assert.equal(f.states.size, 1);
    assert.equal(f.states.get('/' + name).nodeId, 'old');
    assert.equal(fs.readFileSync(path.join(f.root, name), 'utf8'), 'new-cloud');
  }
  const bases = path.join(userData, 'sync-recovery/root-1');
  const rounds = fs.readdirSync(bases).filter(name => name !== 'legacy-old');
  assert.equal(rounds.length, 4);
  for (const name of rounds) assert.equal(JSON.parse(fs.readFileSync(path.join(bases, name, 'manifest.json'))).status, 'complete');
  assert.equal(fs.readFileSync(path.join(fixed, 'files/old.txt'), 'utf8'), 'old-original');
  assert.ok(rounds.some(name => fs.existsSync(path.join(bases, name, 'files/old.txt'))
    && fs.readFileSync(path.join(bases, name, 'files/old.txt'), 'utf8') === 'current-original'));
});

test('升级前固定ID已移动但pending清单可续写，重试不重复搬移', async t => {
  const f = fixture(t);
  const { recovery } = realRecovery(f);
  const backup = recovery.preserveAndRemove('root-1', 'legacy-old', f.root,
    path.join(f.root, 'old.txt'), '/old.txt', () => {});
  const manifestPath = path.join(backup, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath));
  fs.writeFileSync(manifestPath, JSON.stringify({ ...manifest, status: 'pending' }));
  f.respond(() => ({ code: 200, data: { pages: '1', records: [{ id: 'old', parentId: 'root',
    nodeType: 1, name: 'new.txt', fileSize: 9, fileMd5: 'new-md5', updatedAt: '2020-01-01T00:00:00Z' }] } }));
  assert.equal(await f.run(), true, JSON.stringify(f.logs));
  assert.equal(JSON.parse(fs.readFileSync(manifestPath)).status, 'complete');
  assert.equal(fs.readFileSync(path.join(backup, 'files/old.txt'), 'utf8'), 'old-original');
  assert.equal(await f.run(), true);
  assert.equal(f.states.has('/old.txt'), false);
});
