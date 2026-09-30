const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { Readable } = require('node:stream');
const desktop = path.resolve(__dirname, '../../../st-desktop');
const ts = require(path.join(desktop, 'node_modules/typescript'));
function load(relative, dependencies) {
  const output = {};
  const code = ts.transpileModule(fs.readFileSync(path.join(desktop, 'src', relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  vm.runInNewContext(code, { exports: output, Buffer, process, console, require(name) {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
    return dependencies[name];
  } });
  return output;
}
const md5 = bytes => crypto.createHash('md5').update(bytes).digest('hex');

async function refreshProbe() {
  let handler, registered = 'R0';
  const attempts = [];
  const client = async () => ({ status: 200 });
  client.interceptors = { request: { use() {} }, response: { use(_success, failure) { handler = failure; } } };
  client.defaults = {};
  const axios = { create: () => client, async post(_url, body) {
    attempts.push(body.refreshToken);
    if (body.refreshToken !== registered) throw Error('TOKEN_INVALID');
    registered = 'R' + attempts.length;
    return { data: { data: { token: 'A' + attempts.length, refreshToken: registered } } };
  } };
  const api = load('api-client.ts', { axios, './server-config': { getServerUrl: () => 'http://fixture.invalid' } });
  api.setAuth('A0', 'R0');
  const expired = () => ({ response: { status: 401 }, config: { headers: {} } });
  await handler(expired());
  let secondFailed = false;
  try { await handler(expired()); } catch { secondFailed = true; }
  console.log(JSON.stringify({ probe: 'rotating-refresh', attempts, registered, secondFailed }));
}

async function deltaProbe(scenario) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-review-delta-'));
  try {
    const root = path.join(temporary, 'root'); fs.mkdirSync(root);
    const states = new Map([['/old.txt', { rootId: 'R', localPath: '/old.txt', nodeId: 'N', md5: md5('a'), localMtime: 1 }]]);
    if (scenario === 'delete') fs.writeFileSync(path.join(root, 'old.txt'), 'a');
    if (scenario === 'move-replay') {
      fs.writeFileSync(path.join(root, 'new.txt'), 'bb');
      states.set('/new.txt', { rootId: 'R', localPath: '/new.txt', nodeId: 'N', md5: md5('bb'), localMtime: fs.statSync(path.join(root, 'new.txt')).mtimeMs });
    }
    let config = { rootId: 'R', localPath: root, cursor: '1', status: 'active', syncVersion: 4, lastSyncAt: 1 };
    const logs = [], downloads = [];
    const item = { logId: '2', nodeId: 'N', parentId: 'ROOT', nodeType: 1,
      changeType: scenario === 'delete' ? 'DELETE' : 'MOVE', path: scenario === 'delete' ? '/old.txt' : '/new.txt',
      oldPath: scenario === 'delete' ? null : '/old.txt', size: 1, md5: md5('a'), updatedAt: '2020-01-01T00:00:00Z' };
    const apiClient = { async get(url) {
      if (url.includes('/delta')) return { data: { data: { cursor: '2', hasMore: false, scopeProjectionVersion: 2, changes: [item] } } };
      if (url === '/file/ROOT') return { data: { code: 200, data: { path: '/cloud/root', status: 0, updatedAt: '2020-01-01T00:00:00Z' } } };
      if (url === '/file/N') return scenario === 'delete' ? { data: { code: 403, message: '没有权限访问' } }
        : { data: { code: 200, data: { path: '/cloud/root/new.txt', status: 0, fileSize: '2', fileMd5: md5('bb'), updatedAt: '2020-02-01T00:00:00Z' } } };
      if (url === '/file/N/stream') { downloads.push(url); return { data: Readable.from(['bb']) }; }
      throw Error('Unexpected URL ' + url);
    } };
    const database = {
      getSyncConfig: () => config, upsertSyncConfig: row => { config = { ...config, ...row }; },
      getSyncState: (_root, rel) => states.get(rel), getAllSyncStates: () => [...states.values()],
      upsertSyncState: row => states.set(row.localPath, row), deleteSyncState: (_root, rel) => states.delete(rel),
      insertSyncHistory() {},
    };
    const shared = { withRetry: fn => fn(), syncLog: (_level, message) => logs.push(message), emitSyncEvent() {},
      parseFileSize: value => value == null ? null : Number(value), SYNC_ENGINE_VERSION: 4, ENGINE_WRITE_TTL_MS: 30000 };
    const download = load('sync/sync-download.ts', { fs, os, path, crypto, '../api-client': { apiClient }, '../database': database,
      '../utils/md5': { calculateFileMd5: async file => md5(fs.readFileSync(file)) }, '../sync-utils': {}, './sync-shared': shared });
    const { SyncEngine } = load('sync-engine.ts', {
      fs, path, crypto, './api-client': { apiClient }, './database': database,
      './file-watcher': { FileWatcher: class {} }, './utils/md5': {}, './sync-retry': {},
      './sync-utils': { isIgnoredLocalPath: () => false }, './sync/sync-shared': shared,
      './sync/sync-upload': {}, './sync/sync-download': download, './sync/sync-reconcile': {}, './sync/sync-recovery': {},
    });
    const engine = new SyncEngine({ rootId: 'R', localPath: root, cloudFolderNodeId: 'ROOT' });
    engine.scanLocalChanges = async () => {};
    await engine.syncOnce(); await engine.syncOnce();
    console.log(JSON.stringify({ probe: scenario, cursor: config.cursor, downloads: downloads.length,
      states: [...states.keys()], error: logs.filter(line => line.includes('同步失败')).at(-1),
      targetExists: fs.existsSync(path.join(root, 'new.txt')) }));
  } finally {
    if (path.dirname(temporary) !== path.resolve(os.tmpdir())) throw Error('Unsafe fixture cleanup');
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
(async () => {
  await refreshProbe();
  for (const scenario of ['delete', 'move-historical-content', 'move-replay']) await deltaProbe(scenario);
})().catch(error => { console.error(error); process.exitCode = 1; });
