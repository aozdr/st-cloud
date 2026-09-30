const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { Readable } = require('node:stream');
const ts = require('typescript');

function load(file, dependencies) {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  const exports = {};
  vm.runInNewContext(code, { exports, Buffer, console: { error() {} }, require(name) {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
    return dependencies[name];
  } });
  return exports;
}

test('TC04-21: delta 与目录大小完整矩阵，下载按实际字节验证且 ID/游标保持字符串', async t => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-size-test-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const root = path.join(temporary, 'root');
  fs.mkdirSync(root);
  const rootId = '9007199254740993';
  const nodeId = '9007199254740995';
  const cursor = '9007199254740997';
  const states = new Map();
  const history = [];
  let currentSize = '3';
  let currentBytes = 'abc';
  let listName = 'first.txt';
  const apiClient = { get: async url => {
    if (url.includes('/delta')) return { data: { data: {
      cursor, hasMore: false, changes: [{ nodeId, size: currentSize }],
    } } };
    if (url === '/file/list') return { data: { code: 200, data: { pages: 1, records: [{
      id: nodeId, parentId: 'folder', nodeType: 1, name: listName,
      fileSize: currentSize, fileMd5: null, updatedAt: '2020-01-01T00:00:00Z',
    }] } } };
    if (url === `/file/${nodeId}/stream`) return { data: Readable.from([currentBytes]) };
    throw Error('Unexpected URL ' + url);
  } };
  const database = {
    getSyncState: (_root, rel) => states.get(rel),
    upsertSyncState: value => states.set(value.localPath, value),
    insertSyncHistory: value => history.push(value),
  };
  const shared = load('sync-shared.ts', { electron: { BrowserWindow: { getAllWindows: () => [] } } });
  const downloads = load('sync-download.ts', {
    fs, os, path, crypto: require('node:crypto'), '../api-client': { apiClient },
    '../database': database, '../utils/md5': { calculateFileMd5: async () => '' },
    '../sync-utils': {}, './sync-shared': shared,
  });
  const reconcile = load('sync-reconcile.ts', {
    fs, path, '../api-client': { apiClient },
    '../database': { ...database, getAllSyncStates: () => [...states.values()] },
    '../utils/md5': { calculateFileMd5: async () => '' },
    './sync-shared': shared, './sync-recovery': {},
  });
  const engine = Object.create(load('../sync-engine.ts', {
    fs, path, crypto: require('node:crypto'), './api-client': { apiClient },
    './database': database, './file-watcher': {}, './utils/md5': {}, './sync-retry': {},
    './sync-utils': {}, './sync/sync-shared': shared, './sync/sync-upload': {},
    './sync/sync-download': {}, './sync/sync-reconcile': {}, './sync/sync-recovery': {},
  }).SyncEngine.prototype);
  engine.root = { rootId, localPath: root, cloudFolderNodeId: 'folder' };
  const ctx = {
    root: engine.root, isExcluded: () => false,
    absPathFor: rel => path.join(root, ...rel.split('/').filter(Boolean)),
    markEngineWritten() {},
    downloadFile: (item, abs, rel) => downloads.downloadFile(ctx, item, abs, rel),
  };

  for (const [size, bytes, name, expected] of [
    ['3', 'abc', 'three.txt', 3], ['0', '', 'zero.txt', 0], [null, 'abc', 'unknown.txt', 3],
  ]) {
    currentSize = size; currentBytes = bytes; listName = name;
    const delta = await engine.fetchDelta('9007199254740991');
    assert.equal(delta.cursor, cursor);
    assert.equal(typeof delta.cursor, 'string');
    assert.equal(delta.changes[0].nodeId, nodeId);
    assert.equal(typeof delta.changes[0].nodeId, 'string');
    assert.equal(delta.changes[0].size, size == null ? null : expected);
    assert.equal(await reconcile.reconcileFolder(ctx, 'folder', ''), 1);
    assert.equal(fs.statSync(path.join(root, name)).size, expected);
    assert.equal(states.get('/' + name).nodeId, nodeId);
    assert.equal(history.at(-1).status, 'success');
  }
  for (const size of ['-1', 'abc', '9007199254740992', 1.5]) {
    currentSize = size; listName = 'invalid.txt';
    await assert.rejects(engine.fetchDelta('9007199254740991'), /文件大小/);
    await assert.rejects(reconcile.reconcileFolder(ctx, 'folder', ''), /文件大小/);
    assert.equal(fs.existsSync(path.join(root, 'invalid.txt')), false);
  }
});
