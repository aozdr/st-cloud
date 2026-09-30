const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../sync-engine.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
}).outputText;

test('TC04-10: 越界路径和目录联接不允许解析到根外', async t => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-path-test-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const root = path.join(temporary, 'root');
  const outside = path.join(temporary, 'outside');
  fs.mkdirSync(root); fs.mkdirSync(outside);
  const sentinel = path.join(outside, 'sentinel.txt');
  fs.writeFileSync(sentinel, 'outside-original');
  const persisted = [];
  let relativePath = '/linked/sentinel.txt';
  const dependencies = {
    fs, path, crypto: require('node:crypto'),
    './api-client': { apiClient: { get: async () => ({ data: { data: {
      cursor: '2', hasMore: false, scopeProjectionVersion: 2,
      changes: [{ path: relativePath, nodeId: 'N', changeType: 'DELETE', nodeType: 1 }],
    } } }) } },
    './database': {
      getSyncConfig: () => ({ cursor: '1' }),
      upsertSyncConfig: row => persisted.push(row),
    },
    './file-watcher': { FileWatcher: class { constructor() {} } },
    './utils/md5': {}, './sync-retry': {}, './sync-utils': {},
    './sync/sync-shared': { syncLog() {}, emitSyncEvent() {}, withRetry: fn => fn(),
      parseFileSize: value => value, SYNC_ENGINE_VERSION: 4 },
    './sync/sync-upload': {}, './sync/sync-download': {},
    './sync/sync-reconcile': {}, './sync/sync-recovery': {},
  };
  const exports = {};
  vm.runInNewContext(code, { exports, require(name) {
    if (!(name in dependencies)) throw Error(`Unexpected dependency: ${name}`);
    return dependencies[name];
  } });
  const engine = new exports.SyncEngine({ rootId: 'R', localPath: root, cloudFolderNodeId: 'N' });
  assert.equal(engine.absPathFor('/safe/note.txt'), path.join(root, 'safe', 'note.txt'));
  assert.equal(engine.absPathFor('/../outside/sentinel.txt'), null);
  assert.equal(engine.absPathFor('/safe/../note.txt'), null);
  assert.equal(engine.absPathFor('C:\\outside\\sentinel.txt'), null);
  assert.equal(engine.absPathFor('/C:/outside/sentinel.txt'), null);
  const link = path.join(root, 'linked');
  fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
  assert.equal(engine.absPathFor('/linked/sentinel.txt'), null);
  for (relativePath of ['/../outside/sentinel.txt', '/linked/sentinel.txt']) {
    await engine.syncOnce();
    assert.equal(persisted.length, 0);
  }
  assert.equal(fs.readFileSync(sentinel, 'utf8'), 'outside-original');
});
