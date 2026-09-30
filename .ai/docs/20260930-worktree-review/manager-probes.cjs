const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const crypto = require('node:crypto');
const desktop = path.resolve(__dirname, '../../../st-desktop');
const ts = require(path.join(desktop, 'node_modules/typescript'));
function load(relative, dependencies, globals = {}) {
  const exports = {};
  const code = ts.transpileModule(fs.readFileSync(path.join(desktop, 'src', relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  vm.runInNewContext(code, { exports, console, Buffer, setImmediate, ...globals, require(name) {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
    return dependencies[name];
  } });
  return exports;
}
async function probe(scenario) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-review-manager-'));
  try {
    const root = path.join(temporary, 'root'), userData = path.join(temporary, 'userData');
    fs.mkdirSync(root); fs.mkdirSync(userData);
    let config = { rootId: 'R', localPath: root, cursor: '1', status: 'active', syncVersion: 3, lastSyncAt: 1 };
    const states = new Map(), requests = [];
    if (scenario === 'excluded-upgrade') {
      fs.writeFileSync(path.join(root, 'excluded.txt'), 'original-excluded');
      states.set('/excluded.txt', { rootId: 'R', localPath: '/excluded.txt', nodeId: 'N' });
    }
    const database = { getSyncConfig: () => config, upsertSyncConfig: row => { config = { ...config, ...row }; },
      getSyncState: (_root, rel) => states.get(rel), getAllSyncStates: () => [...states.values()],
      upsertSyncState: row => states.set(row.localPath, row), deleteSyncState: (_root, rel) => states.delete(rel),
      getAllSyncConfigs: () => [config], insertSyncHistory() {} };
    const apiClient = { async get(url) {
      requests.push(url);
      if (url === '/file/list') return { data: { code: 200, data: { records: [], pages: 0 } } };
      if (url === '/file/ROOT') return { data: { code: 200, data: { path: '/cloud/root' } } };
      if (url === '/file/N') return { data: { code: 200, data: { path: '/outside/excluded.txt', status: 0 } } };
      if (url.endsWith('/delta')) return { data: { data: { cursor: '2', hasMore: false, scopeProjectionVersion: 2, changes: [] } } };
      if (url.endsWith('/exclusions')) return { data: { data: [{ relativePath: '/excluded.txt' }] } };
      throw Error('Unexpected URL ' + url);
    } };
    const shared = { withRetry: fn => fn(), syncLog() {}, emitSyncEvent() {}, parseFileSize: value => value,
      SYNC_ENGINE_VERSION: 4, ENGINE_WRITE_TTL_MS: 30000 };
    const recovery = load('sync/sync-recovery.ts', { fs, path, electron: { app: { getPath: () => userData } }, '../database': database });
    const actualReconcile = load('sync/sync-reconcile.ts', { fs, path, '../api-client': { apiClient }, '../database': database,
      '../utils/md5': {}, './sync-shared': shared, './sync-recovery': recovery });
    let reconciliations = 0, recovered = false, watcherStarts = 0, timers = 0;
    const reconcile = { ...actualReconcile, async fullReconcile(ctx) {
      reconciliations++;
      return scenario === 'failed-start' ? recovered : actualReconcile.fullReconcile(ctx);
    } };
    const engine = load('sync-engine.ts', { fs, path, crypto, './api-client': { apiClient }, './database': database,
      './file-watcher': { FileWatcher: class { setHandler() {} async start() { watcherStarts++; } async stop() {} } },
      './utils/md5': {}, './sync-retry': {}, './sync-utils': { isIgnoredLocalPath: () => false }, './sync/sync-shared': shared,
      './sync/sync-upload': {}, './sync/sync-download': {}, './sync/sync-reconcile': reconcile, './sync/sync-recovery': recovery,
    }, { setInterval: () => { timers++; return 1; }, clearInterval() {} });
    const manager = load('sync-manager.ts', { electron: { BrowserWindow: { getAllWindows: () => [] } },
      './api-client': { apiClient }, './sync-engine': engine, './database': database,
      './ws-client': { SyncWsClient: class { onChange() {} onStatus() {} start() {} stop() {} } },
    });
    await manager.startSync('R', 'ROOT', root);
    recovered = true;
    await manager.startSync('R', 'ROOT', root);
    const saved = path.join(userData, 'sync-recovery/R/legacy-N/files/excluded.txt');
    console.log(JSON.stringify({ probe: scenario, reconciliations, running: manager.isSyncing('R'), watcherStarts, timers,
      excludedSourceExists: fs.existsSync(path.join(root, 'excluded.txt')), savedExcluded: fs.existsSync(saved) ? fs.readFileSync(saved, 'utf8') : null, requests }));
    await manager.stopAllSync();
  } finally {
    if (path.dirname(temporary) !== path.resolve(os.tmpdir())) throw Error('Unsafe fixture cleanup');
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
(async () => { await probe('failed-start'); await probe('excluded-upgrade'); })().catch(error => { console.error(error); process.exitCode = 1; });
