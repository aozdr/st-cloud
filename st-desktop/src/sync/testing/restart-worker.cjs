// 独立测试进程：实际引擎/恢复模块/SQLite，网络仅提供固定的已删除节点事件。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const [directory, mode, scenario = 'delete'] = process.argv.slice(2);
const rootId = '9007199254740993';
const otherRootId = '9007199254740995';
const oldCursor = '9007199254740997';
const nextCursor = '9007199254740999';
const logId = '9007199254741001';
const nodeId = '9007199254741003';
const folderScenario = scenario.startsWith('folder-');
const hasOldFolder = ['folder-modified', 'folder-export', 'folder-unchanged'].includes(scenario);
const nestedId = '9200000000000001';
const deepId = '9200000000000002';
const emptyId = '9200000000000003';
const excludedId = '9200000000000004';
const cloudFiles = new Map();
const folderFileRecords = Array.from({ length: 101 }, (_, index) => {
  const id = String(9100000000000000n + BigInt(index));
  const name = `file-${String(index).padStart(3, '0')}.txt`;
  const bytes = 'cloud-file-' + index;
  const record = { id, name, parentId: nodeId, nodeType: 1, fileSize: String(bytes.length),
    fileMd5: require('node:crypto').createHash('md5').update(bytes).digest('hex'), updatedAt: '2020-01-01T00:00:00Z' };
  cloudFiles.set(id, { record, bytes });
  return record;
});
const leafId = '9200000000000005';
cloudFiles.set(leafId, { bytes: 'deep-leaf', record: { id: leafId, parentId: deepId, name: 'leaf.txt', nodeType: 1,
  fileSize: '9', fileMd5: require('node:crypto').createHash('md5').update('deep-leaf').digest('hex'), updatedAt: '2020-01-01T00:00:00Z' } });
const directoryRecord = (id, name) => ({ id, parentId: nodeId, name, nodeType: 0, fileSize: null,
  fileMd5: null, updatedAt: '2020-01-01T00:00:00Z' });
const invalidOld = scenario.startsWith('invalid-old-');
const outside = path.join(directory, 'outside');
const replacementNodeId = '9007199254741009';
const reconcileScenario = scenario.startsWith('reconcile');
const rootReconcile = scenario.startsWith('reconcile-root-');
const upgradeScenario = scenario === 'upgrade';
const staleScenario = scenario.startsWith('stale-');
const staleInside = staleScenario && scenario.endsWith('-inside');
const crossRoot = scenario === 'cross-root';
const conflictScenario = scenario.startsWith('conflict-');
const conflictReconcile = scenario.startsWith('conflict-reconcile-');
const conflictReplay = scenario === 'conflict-replay';
const watcherScenario = scenario.startsWith('watcher-');
const exclusionScenario = scenario === 'watcher-exclusion';
const taskScenario = conflictScenario || watcherScenario;
const identityScenario = scenario.startsWith('identity-');
const sameIdentity = scenario === 'identity-same';
const moveScenario = /^(move|rename)(-|$)/.test(scenario) || scenario === 'watcher-move';
const currentRootPath = scenario === 'reconcile-root-move' ? '/another/root' : '/cloud/renamed';
const retainedScenario = ['delete-returned', 'delete-reused', 'reconcile-reused'].includes(scenario);
const historyScenario = scenario.startsWith('history-');
const integrityScenario = ['race-same', 'race-size', 'truncate', 'tamper'].includes(scenario);
const contentScenario = ['move', 'download', 'create', 'two-events'].includes(scenario) || moveScenario || identityScenario || historyScenario || integrityScenario || invalidOld || conflictScenario || scenario === 'watcher-move';
const newerBytes = scenario === 'race-size' ? 'cloud-v3-longer' : 'cloud-v3';
const cloudBytes = moveScenario || retainedScenario || crossRoot ? 'original-A'
  : scenario.startsWith('race-') && mode !== 'fail-content' ? newerBytes : 'cloud-v2';
const cloudMd5 = require('node:crypto').createHash('md5').update(cloudBytes).digest('hex');
const badPath = scenario.endsWith('-parent') ? '/../outside/sentinel.txt'
  : scenario.endsWith('-drive') ? path.join(outside, 'sentinel.txt') : '/linked/sentinel.txt';
const targetRelative = scenario.startsWith('invalid-current-') ? badPath
  : folderScenario ? scenario === 'folder-export' ? '/oldfolder' : '/newfolder'
  : moveScenario || invalidOld || identityScenario ? '/moved.txt' : '/note.txt';
const operationId = reconcileScenario ? 'legacy-' + nodeId
  : scenario === 'historical' ? logId + '-' + require('node:crypto').createHash('sha256').update('/note.txt').digest('hex').slice(0, 16)
  : logId;
const root = path.join(directory, 'root');
const other = path.join(directory, 'other');
const userData = path.join(directory, 'userData');
const dbPath = path.join(directory, 'sync.sqlite');
const recovery = path.join(userData, 'sync-recovery', rootId, operationId);
const logs = [];
const requests = [];
let listRecovered = false;
let directError;
const attempts = [];
const cloudUploadsPath = path.join(directory, 'cloud-uploads.json');
const cloudUploads = fs.existsSync(cloudUploadsPath) ? JSON.parse(fs.readFileSync(cloudUploadsPath, 'utf8')) : [];
const pendingUploads = new Map();
const watcherEvents = [];
let watcherInitialRequests = [];
let failWatcherDelta = false;
let watcherFailureSnapshot;
function tree(directory, relative = '') {
  if (!fs.existsSync(directory)) return {};
  const result = {};
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const name = relative + entry.name;
    if (entry.isDirectory()) { result[name + '/'] = 'directory'; Object.assign(result, tree(path.join(directory, entry.name), name + '/')); }
    else if (entry.isFile()) result[name] = fs.readFileSync(path.join(directory, entry.name), 'utf8');
  }
  return result;
}

(async () => {
  const SQL = await require('sql.js')({ locateFile: file => require.resolve('sql.js/dist/' + file) });
  const database = fs.existsSync(dbPath) ? new SQL.Database(fs.readFileSync(dbPath)) : new SQL.Database();
  const cache = new Map();
  const overrides = new Map();
  const sourceDir = path.resolve(__dirname, '../..');
  let taskStore;
  let uploadClock = 0;
  function load(relative) {
    const file = path.resolve(sourceDir, relative);
    if (overrides.has(file)) return overrides.get(file);
    if (cache.has(file)) return cache.get(file);
    const exports = {};
    cache.set(file, exports);
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
    }).outputText;
    const uploadTimeout = conflictScenario && mode === 'fail-timeout' && file.endsWith('sync-upload.ts');
    const TestDate = class extends Date { static now() {
      if (taskStore?.getAllTasks().some(task => task.status === 'merging')) uploadClock += 600001;
      return uploadClock;
    } };
    vm.runInNewContext(code, { exports, Buffer, Date: uploadTimeout ? TestDate : Date,
      setTimeout: uploadTimeout ? (fn => setTimeout(fn, 5)) : setTimeout, clearTimeout, setInterval, clearInterval,
      setImmediate, console, require: name => {
        if (name === 'fs') return faultFs;
        if (name === 'os' && conflictScenario) return { ...require('node:os'), tmpdir: () => directory };
        if (name === 'electron') return { app: { getPath: () => userData }, BrowserWindow: { getAllWindows: () => [] } };
        if (name.startsWith('.')) return load(path.relative(sourceDir, path.resolve(path.dirname(file), name + '.ts')));
        return require(name);
      } }, { filename: file });
    return exports;
  }
  const faultFs = { ...fs,
    writeFileSync(target, ...args) {
      if (mode === 'manifest-write' && String(target).endsWith('manifest.json.tmp')) throw Error('injected manifest write failure');
      if (mode === 'crash-complete-write' && String(target).endsWith('manifest.json.tmp')
          && !fs.existsSync(path.join(root, 'note.txt'))) process.exit(75);
      return fs.writeFileSync(target, ...args);
    },
    renameSync(from, to) {
      if (mode === 'source-move' && from === path.join(root, 'note.txt')) throw Error('injected source move failure');
      if (mode === 'crash-manifest' && from.endsWith('manifest.json.tmp') && !fs.existsSync(path.join(root, 'note.txt'))) {
        process.exit(73);
      }
      return fs.renameSync(from, to);
    },
  };
  overrides.set(path.join(sourceDir, 'db/db-core.ts'), {
    getDb: () => database, persist: () => fs.writeFileSync(dbPath, Buffer.from(database.export())),
  });
  const meta = load('db/sync-meta.ts');
  if (taskScenario) {
    const ddl = fs.readFileSync(path.join(sourceDir, 'database.ts'), 'utf8').match(/CREATE TABLE IF NOT EXISTS transfer_tasks \([\s\S]*?\n    \)/)[0];
    database.run(ddl);
    taskStore = load('db/transfer-tasks.ts');
  }
  if (!fs.existsSync(dbPath)) {
    for (const folder of [root, other, userData]) fs.mkdirSync(folder, { recursive: true });
    fs.mkdirSync(path.join(outside, rootId, logId), { recursive: true });
    fs.writeFileSync(path.join(outside, 'sentinel.txt'), 'outside-sentinel');
    fs.writeFileSync(path.join(outside, rootId, logId, 'manifest.json.tmp'), 'outside-manifest');
    if (scenario.endsWith('-link')) fs.symlinkSync(outside,
      scenario === 'invalid-recovery-link' ? path.join(userData, 'sync-recovery') : path.join(root, 'linked'),
      process.platform === 'win32' ? 'junction' : 'dir');
    database.run(`CREATE TABLE sync_config (root_id TEXT PRIMARY KEY,local_path TEXT,cursor TEXT,status TEXT,user_id TEXT,last_sync_at INTEGER,sync_version INTEGER,updated_at TEXT);
      CREATE TABLE sync_state (root_id TEXT,local_path TEXT,node_id TEXT,md5 TEXT,size INTEGER,local_mtime REAL,cloud_mtime TEXT,status TEXT,fail_count INTEGER,fail_mtime REAL,next_retry_at INTEGER,updated_at TEXT,PRIMARY KEY(root_id,local_path));
      CREATE TABLE sync_history (id INTEGER PRIMARY KEY AUTOINCREMENT,root_id TEXT,action TEXT,file_name TEXT,rel_path TEXT,status TEXT,detail TEXT,created_at TEXT);`);
    if (scenario !== 'create' && !folderScenario) fs.writeFileSync(path.join(root, 'note.txt'), 'original-A');
    fs.writeFileSync(path.join(other, 'sentinel.txt'), 'original-B');
    meta.upsertSyncConfig({ rootId, localPath: root, cursor: oldCursor, syncVersion: 4 });
    meta.upsertSyncConfig({ rootId: otherRootId, localPath: other, cursor: '9223372036854775806', syncVersion: 4 });
    if (scenario !== 'create' && !folderScenario) meta.upsertSyncState({ rootId, localPath: '/note.txt',
      nodeId: scenario === 'delete-reused' ? replacementNodeId : nodeId,
      md5: moveScenario || retainedScenario ? cloudMd5 : 'old',
      localMtime: fs.statSync(path.join(root, 'note.txt')).mtimeMs, status: 'synced' });
    meta.upsertSyncState({ rootId: otherRootId, localPath: '/sentinel.txt', nodeId: '9007199254741005', status: 'synced' });
    if (crossRoot) meta.upsertSyncState({ rootId: otherRootId, localPath: '/sentinel.txt', nodeId: '9007199254741005',
      md5: require('node:crypto').createHash('md5').update('original-B').digest('hex'),
      localMtime: fs.statSync(path.join(other, 'sentinel.txt')).mtimeMs, status: 'synced' });
    if (upgradeScenario) {
      meta.upsertSyncConfig({ rootId, localPath: root, cursor: oldCursor, syncVersion: 1 });
      fs.writeFileSync(path.join(root, 'unknown.txt'), 'untracked-local');
      fs.writeFileSync(path.join(root, 'note.txt'), 'modified-local');
    }
    if (conflictScenario) {
      meta.upsertSyncState({ rootId, localPath: '/note.txt', nodeId,
        md5: 'baseline-md5', localMtime: 1, status: 'synced' });
      if (scenario.endsWith('-missing')) meta.deleteSyncState(rootId, '/note.txt');
      if (scenario.endsWith('-other')) meta.upsertSyncState({ rootId, localPath: '/note.txt', nodeId: replacementNodeId });
      if (conflictReconcile) meta.upsertSyncConfig({ rootId, localPath: root, cursor: oldCursor, syncVersion: 1 });
    }
    if (identityScenario) {
      if (scenario === 'identity-unknown') meta.deleteSyncState(rootId, '/note.txt');
      else meta.upsertSyncState({ rootId, localPath: '/note.txt', nodeId: replacementNodeId,
        md5: require('node:crypto').createHash('md5').update('original-A').digest('hex'),
        localMtime: fs.statSync(path.join(root, 'note.txt')).mtimeMs, status: 'synced' });
    }
    if (scenario.endsWith('-missing')) { if (moveScenario) fs.unlinkSync(path.join(root, 'note.txt')); }
    if (moveScenario && scenario.endsWith('-collision')) {
      fs.writeFileSync(path.join(root, 'moved.txt'), 'belongs-to-X');
      meta.upsertSyncState({rootId, localPath: '/moved.txt', nodeId: replacementNodeId, md5: 'X-hash',
        localMtime: fs.statSync(path.join(root, 'moved.txt')).mtimeMs, status: 'synced'});
    }
    if (watcherScenario) meta.upsertSyncConfig({ rootId, localPath: root, cursor: oldCursor, syncVersion: 4, lastSyncAt: Date.now() });
    if (hasOldFolder) {
      const sourceFolder = path.join(root, 'oldfolder');
      fs.mkdirSync(sourceFolder);
      fs.writeFileSync(path.join(sourceFolder, 'managed.txt'), 'old-local');
      fs.writeFileSync(path.join(sourceFolder, 'unknown.txt'), 'unknown-local');
      const parentStat = fs.statSync(sourceFolder);
      meta.upsertSyncState({ rootId, localPath: '/oldfolder', nodeId,
        localMtime: scenario === 'folder-modified' ? parentStat.mtimeMs - 1000 : parentStat.mtimeMs, status: 'synced' });
      meta.upsertSyncState({ rootId, localPath: '/oldfolder/managed.txt', nodeId: '9200000000000010',
        md5: 'old-hash', localMtime: fs.statSync(path.join(sourceFolder, 'managed.txt')).mtimeMs, status: 'synced' });
      if (scenario !== 'folder-unchanged') fs.writeFileSync(path.join(sourceFolder, 'managed.txt'), 'edited-local');
    }
    if (scenario === 'folder-collision') {
      fs.mkdirSync(path.join(root, 'newfolder'));
      fs.writeFileSync(path.join(root, 'newfolder/occupied.txt'), 'belongs-to-X');
      meta.upsertSyncState({ rootId, localPath: '/newfolder', nodeId: replacementNodeId, status: 'synced' });
    }
  }
  overrides.set(path.join(sourceDir, 'database.ts'), { ...meta, ...taskStore,
    upsertSyncConfig(row) {
      if (mode === 'crash-cursor' && row.rootId === rootId && row.cursor === nextCursor) process.exit(74);
      return meta.upsertSyncConfig(row);
    },
  });
  if (watcherScenario) {
    const { FileWatcher } = load('file-watcher.ts');
    overrides.set(path.join(sourceDir, 'file-watcher.ts'), { FileWatcher: class extends FileWatcher {
      setHandler(handler) { super.setHandler(events => { watcherEvents.push(...events); handler(events); }); }
    } });
  } else overrides.set(path.join(sourceDir, 'file-watcher.ts'), { FileWatcher: class {
    setHandler() {} async start() {} async stop() {}
  } });
  if (!taskScenario) overrides.set(path.join(sourceDir, 'upload-manager.ts'), { startUpload: async () => { throw Error('unexpected upload'); } });
  overrides.set(path.join(sourceDir, 'api-client.ts'), { apiClient: {
    post: async (url, body, options) => {
      requests.push({ method: 'POST', url, body: Buffer.isBuffer(body) ? body.toString() : body, params: options?.params });
      if (!taskScenario) throw Error('unexpected mutation ' + url);
      if (url === '/file/upload/check') return { data: { code: 200, data: { instant: false } } };
      if (url === '/file/upload/init') {
        if (['fail-upload', 'fail-both'].includes(mode)) throw Error('injected upload init failure');
        const id = String(9300000000000000n + BigInt(cloudUploads.length + pendingUploads.size));
        pendingUploads.set(id, { ...body, id, bytes: '' });
        return { data: { code: 200, data: { uploadId: id, fileId: body.replaceFileId || id, s3UploadId: id, transferMode: 'relay', relayChunkSize: 1024 } } };
      }
      if (url === '/file/upload/relay-chunk') {
        pendingUploads.get(options.params.uploadId).bytes += body.toString();
        return { data: { code: 200 } };
      }
      if (url === '/file/upload/relay-finalize') {
        if (mode === 'fail-timeout') return new Promise(() => {});
        cloudUploads.push(pendingUploads.get(options.params.uploadId));
        fs.writeFileSync(cloudUploadsPath, JSON.stringify(cloudUploads));
        return { data: { code: 200 } };
      }
      throw Error('unexpected mutation ' + url);
    }, get: async (url, options) => {
    requests.push({ url, since: options?.params?.since, parentId: options?.params?.parentId, page: options?.params?.page });
    if (watcherScenario && failWatcherDelta && url.includes('/delta')) throw Error('injected watcher cloud failure');
    if (conflictScenario) {
      const uploaded = cloudUploads.find(row => !row.replaceFileId && (url === '/file/' + row.id || url === '/file/' + row.id + '/stream'));
      if (uploaded && mode === 'fail-copy-detail') throw Error('injected saved copy detail failure');
      if (uploaded) return url.endsWith('/stream') ? { data: require('node:stream').Readable.from([uploaded.bytes]) }
        : { data: { code: 200, data: { path: '/cloud/root/' + uploaded.fileName, status: 0,
          fileSize: String(uploaded.bytes.length), fileMd5: uploaded.fileMd5, updatedAt: '2020-01-01T00:00:00Z' } } };
    }
    if (crossRoot && url.includes('/delta')) {
      const isOther = url.includes(otherRootId);
      if (!isOther && mode === 'fail-root-A') throw Error('injected root A network failure');
      return { data: { data: { cursor: isOther ? '9223372036854775807' : nextCursor,
        hasMore: false, scopeProjectionVersion: 2,
        changes: [{ logId, nodeId, path: '/note.txt', oldPath: null, changeType: isOther ? 'CREATE' : 'DELETE',
          nodeType: 1, size: cloudBytes.length, md5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z' }],
      } } };
    }
    if (crossRoot && (url === '/file/folderB' || url === '/file/' + nodeId)) return { data: { code: 200, data: {
      path: '/cloud/B' + (url === '/file/folderB' ? '' : '/note.txt'), status: 0,
      fileSize: String(cloudBytes.length), fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z',
    } } };
    if (staleScenario && url.includes('/delta')) return { data: { data: {
      cursor: nextCursor, hasMore: false, scopeProjectionVersion: 2,
      changes: [{ logId, nodeId, path: '/note.txt', oldPath: scenario.includes('-MOVE-') ? '/previous.txt' : null,
        changeType: scenario.includes('-MOVE-') ? 'MOVE' : 'CREATE', nodeType: 1,
        size: 1, md5: 'historical-hash', updatedAt: '2020-01-01T00:00:00Z' }],
    } } };
    if (staleScenario && url === '/file/' + nodeId) {
      if (mode === 'fail-stale-timeout') throw Error('injected node detail timeout');
      if (mode === 'fail-stale-forbidden') throw Object.assign(Error('injected HTTP 403'), { response: { status: 403 } });
      return { data: { code: 200, data: {
        path: staleInside ? '/cloud/root/renamed.txt' : scenario.endsWith('-trash') ? '/cloud/root/note.txt' : '/Outside/note.txt',
        status: scenario.endsWith('-trash') ? 1 : 0, fileSize: String(cloudBytes.length), fileMd5: cloudMd5,
        updatedAt: '2020-01-01T00:00:00Z',
      } } };
    }
    if (upgradeScenario && url.includes('/delta')) return { data: { data: {
      cursor: nextCursor, hasMore: false, scopeProjectionVersion: 2, changes: [],
    } } };
    if (upgradeScenario && url === '/file/' + nodeId && mode === 'fail-upgrade-detail') throw Error('injected historical detail timeout');
    if (url.includes('/delta') && (historyScenario || scenario === 'two-events' || conflictReplay)) {
      const first = { logId, nodeId, path: '/note.txt', oldPath: null, changeType: 'UPDATE', nodeType: 1,
        size: cloudBytes.length, md5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z' };
      const second = scenario === 'two-events' || conflictReplay ? { ...first, logId: '9007199254741002',
        nodeId: '9007199254741007', path: '/second.txt', changeType: 'CREATE' } : { ...first, logId: '9007199254741002' };
      if (historyScenario) {
        const oldBytes = scenario.endsWith('same-size') ? 'cloud-v1' : 'v1';
        first.md5 = require('node:crypto').createHash('md5').update(oldBytes).digest('hex');
        first.size = oldBytes.length;
      }
      const crossPage = scenario.includes('cross-page');
      const firstPage = options.params.since === oldCursor;
      return { data: { data: { scopeProjectionVersion: 2,
        cursor: crossPage && firstPage ? '9007199254740998' : nextCursor,
        hasMore: crossPage && firstPage,
        changes: crossPage ? [firstPage ? first : second] : [first, second] } } };
    }
    if (exclusionScenario && url.includes('/delta')) return { data: { data: { cursor: nextCursor, hasMore: false, scopeProjectionVersion: 2, changes: [] } } };
    if (url.includes('/delta')) return { data: { data: { cursor: nextCursor, hasMore: false,
      scopeProjectionVersion: 2, reconcileRequired: reconcileScenario || scenario === 'conflict-reconcile-missing',
      changes: reconcileScenario ? [] : [{ logId, nodeId, path: targetRelative,
        oldPath: folderScenario && !['folder-import', 'folder-export'].includes(scenario) ? '/oldfolder'
          : invalidOld ? badPath : moveScenario || identityScenario ? '/note.txt' : null,
        changeType: folderScenario ? scenario === 'folder-export' ? 'DELETE' : scenario === 'folder-import' ? 'CREATE' : 'MOVE'
          : scenario.startsWith('rename') ? 'RENAME' : moveScenario || identityScenario || invalidOld ? 'MOVE' : scenario === 'create' ? 'CREATE'
          : contentScenario || scenario === 'historical' ? 'UPDATE' : 'DELETE',
        nodeType: folderScenario ? 0 : 1, size: contentScenario ? cloudBytes.length : null, md5: contentScenario ? cloudMd5 : null,
        updatedAt: '2020-01-01T00:00:00Z' }] } } };
    if (identityScenario && url === '/file/list') {
      if (mode === 'fail-identity-list') throw Error('injected identity reconciliation failure');
      return { data: { code: 200, data: { records: [
        { id: nodeId, name: 'moved.txt', nodeType: 1, parentId: 'folder', fileSize: String(cloudBytes.length), fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z' },
        ...(scenario === 'identity-unknown' ? [] : [{ id: replacementNodeId, name: 'note.txt', nodeType: 1,
          parentId: 'folder', fileSize: '10', fileMd5: require('node:crypto').createHash('md5').update('original-A').digest('hex'), updatedAt: '2020-01-01T00:00:00Z' }]),
      ], pages: 1 } } };
    }
    if (url === '/file/list' && folderScenario) {
      const parent = options.params.parentId;
      let records = [], pages = 1;
      if (parent === nodeId) {
        pages = 2;
        if (options.params.page === 2 && ['fail-list', 'retry-list'].includes(mode) && !listRecovered) throw Error('injected folder second page failure');
        records = options.params.page === 1
          ? [...folderFileRecords.slice(0, 97), directoryRecord(nestedId, 'nested'), directoryRecord(emptyId, 'empty'), directoryRecord(excludedId, 'excluded')]
          : folderFileRecords.slice(97);
      } else if (parent === nestedId) records = [directoryRecord(deepId, 'deep')];
      else if (parent === deepId) records = [cloudFiles.get(leafId).record];
      else if (parent === excludedId) throw Error('excluded folder must not be listed');
      else if (parent !== emptyId) throw Error('unexpected folder ' + parent);
      return { data: { code: 200, data: { records, pages } } };
    }
    if ((rootReconcile || staleInside || conflictReconcile) && url === '/file/list') {
      if (mode === 'fail-root-list') throw Error('injected root list failure');
      return { data: { code: 200, data: { records: [
        { id: nodeId, name: conflictReconcile ? 'note.txt' : 'renamed.txt', nodeType: 1, parentId: 'folder', fileSize: String(cloudBytes.length),
          fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z' },
        ...(conflictReconcile ? cloudUploads.filter(row => !row.replaceFileId).map(row => ({ id: row.id, name: row.fileName,
          nodeType: 1, parentId: 'folder', fileSize: String(row.bytes.length), fileMd5: row.fileMd5,
          updatedAt: '2020-01-01T00:00:00Z' })) : []),
      ], pages: 1 } } };
    }
    if (rootReconcile && url === '/file/folder') {
      if (mode === 'fail-root-detail') throw Error('injected root detail failure');
      return { data: { code: 200, data: { id: 'folder', path: currentRootPath, status: 0 } } };
    }
    if (url === '/file/list') return { data: { code: 200, data: {
      records: ['delete-returned', 'reconcile-reused'].includes(scenario) ? [{
        id: scenario === 'reconcile-reused' ? replacementNodeId : nodeId,
        name: 'note.txt', nodeType: 1, parentId: 'folder', fileSize: String(cloudBytes.length),
        fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z',
      }] : [], pages: retainedScenario ? 1 : 0,
    } } };
    if (url === '/file/folder') return { data: { code: 200, data: {
      path: '/cloud/root', status: 0, fileSize: null, fileMd5: null, updatedAt: '2020-01-01T00:00:00Z',
    } } };
    if (url === '/file/' + nodeId && folderScenario && scenario !== 'folder-export') return { data: { code: 200, data: {
      path: '/cloud/root/newfolder', status: 0, fileSize: null, fileMd5: null, updatedAt: '2020-01-01T00:00:00Z',
    } } };
    if (url === '/file/' + nodeId) return contentScenario || scenario === 'delete-returned' ? { data: { code: 200, data: {
      path: '/cloud/root' + (sameIdentity ? '/note.txt' : targetRelative), status: 0, fileSize: String(cloudBytes.length),
      fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z',
    } } } : { data: { code: 2001 } };
    if (url === '/file/9007199254741007') return { data: { code: 200, data: {
      path: '/cloud/root/second.txt', status: 0, fileSize: String(cloudBytes.length),
      fileMd5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z',
    } } };
    if (url === '/file/9007199254741007/stream') {
      if (mode === 'fail-second') throw Error('injected second event download failure');
      return { data: require('node:stream').Readable.from([cloudBytes]) };
    }
    if (url === `/file/${nodeId}/stream`) {
      if (mode === 'fail-move-download') throw Error('injected missing source download failure');
      if (conflictScenario && ['fail-download', 'fail-both'].includes(mode)) throw Error('injected cloud copy failure');
      if (crossRoot && mode === 'fail-root-B') throw Error('injected root B network failure');
      const bytes = mode !== 'fail-content' ? cloudBytes : scenario.startsWith('race-') ? newerBytes
        : scenario === 'truncate' ? 'cl' : 'tampered';
      return { data: require('node:stream').Readable.from([bytes]) };
    }
    const streamNode = /^\/file\/([^/]+)\/stream$/.exec(url)?.[1];
    if (streamNode && cloudFiles.has(streamNode)) return { data: require('node:stream').Readable.from([cloudFiles.get(streamNode).bytes]) };
    throw Error('Unexpected API ' + url);
  } } });
  const shared = load('sync/sync-shared.ts');
  shared.syncLog = (type, message) => logs.push({ type, message });
  if (mode !== 'inspect') {
    const { SyncEngine } = load('sync-engine.ts');
    const strategy = scenario.includes('local_wins') ? 'local_wins' : scenario.includes('latest_wins') ? 'latest_wins' : 'keep_both';
    const engine = new SyncEngine({ rootId, cloudFolderNodeId: 'folder', localPath: root, conflictStrategy: strategy });
    if (exclusionScenario) engine.setExclusions(['/excluded']);
    if (folderScenario) engine.setExclusions(['/newfolder/excluded']);
    if (watcherScenario) {
      await engine.start();
      await new Promise(resolve => setTimeout(resolve, 1300));
      watcherInitialRequests = [...requests];
      if (exclusionScenario) {
        fs.mkdirSync(path.join(root, 'excluded/deep'), { recursive: true });
        fs.writeFileSync(path.join(root, 'excluded/deep/local.txt'), 'excluded-local');
        await new Promise(resolve => setTimeout(resolve, 1600));
        fs.renameSync(path.join(root, 'excluded/deep/local.txt'), path.join(root, 'excluded/deep/renamed.txt'));
        await new Promise(resolve => setTimeout(resolve, 1600));
        // 即使旧服务端错误推送排除路径，客户端消费端也不请求节点或下载。
        await engine.processCloudDelta([{ logId, nodeId, path: '/excluded/deep/cloud.txt', oldPath: null,
          changeType: 'CREATE', nodeType: 1, size: 8, md5: cloudMd5 }]);
      }
      const editedPath = scenario === 'watcher-move' ? 'moved.txt' : 'user.txt';
      failWatcherDelta = mode === 'fail-watcher';
      fs.writeFileSync(path.join(root, editedPath), 'user-edited');
      if (failWatcherDelta) {
        const failureDeadline = Date.now() + 10000;
        while (!logs.some(row => row.message.includes('同步失败')) && Date.now() < failureDeadline) {
          await new Promise(resolve => setTimeout(resolve, 100));
        }
        watcherFailureSnapshot = { cloudUploads: cloudUploads.length,
          source: fs.readFileSync(path.join(root, editedPath), 'utf8'), cursor: meta.getSyncConfig(rootId).cursor,
          failed: logs.some(row => row.message.includes('同步失败')) };
        failWatcherDelta = false;
        await engine.syncOnce();
      }
      const deadline = Date.now() + 6000;
      while (!cloudUploads.some(row => row.bytes === 'user-edited') && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      await new Promise(resolve => setTimeout(resolve, 1200));
      await engine.stop();
    }
    else if (upgradeScenario || (conflictReconcile && scenario !== 'conflict-reconcile-missing')) {
      // 失败启动现在显式抛错；故障夹具仍输出持久化快照，成功模式不得吞掉意外失败。
      try { await engine.start(); }
      catch (error) {
        if (mode === 'resume') throw error;
        directError = error.message;
      }
      await engine.stop();
    }
    else if (sameIdentity) {
      // 直接消费脏事件，刻意令详情与日志同路径以穿过历史事件过滤，验证身份门禁先于同路径刷新。
      try { await engine.processCloudDelta([{ logId, nodeId, path: '/note.txt', oldPath: '/note.txt',
        changeType: 'MOVE', nodeType: 1, size: cloudBytes.length, md5: cloudMd5, updatedAt: '2020-01-01T00:00:00Z' }]); }
      catch (error) { directError = error.message; }
    }
    else await engine.syncOnce();
    if (crossRoot) {
      const otherEngine = new SyncEngine({ rootId: otherRootId, cloudFolderNodeId: 'folderB', localPath: other });
      await otherEngine.syncOnce();
    }
    if (mode === 'retry-list') {
      attempts.push({ cursor: meta.getSyncConfig(rootId).cursor, states: meta.getAllSyncStates(rootId) });
      listRecovered = true;
      await engine.syncOnce();
    }
  }
  // 对账保全按持久基线分轮；从真实清单观察结果，不在测试中复制生产 ID 算法。
  let snapshotRecovery = recovery;
  const recoveryRoot = path.join(userData, 'sync-recovery', rootId);
  if (reconcileScenario && fs.existsSync(recoveryRoot)) {
    const operations = fs.readdirSync(recoveryRoot).filter(name => name.startsWith('legacy-' + nodeId + '-'));
    if (operations.length === 1) snapshotRecovery = path.join(recoveryRoot, operations[0]);
    else if (operations.length > 1) throw Error('ambiguous fixture recovery round');
  }
  const manifestPath = path.join(snapshotRecovery, 'manifest.json');
  const saved = path.join(snapshotRecovery, 'files/note.txt');
  const snapshot = {
    cloudUploads, directError,
    watcherEvents, watcherInitialRequests, watcherFailureSnapshot,
    tasks: taskStore?.getAllTasks().map(task => ({ ...task, sourceBytes: fs.existsSync(task.filePath) ? fs.readFileSync(task.filePath, 'utf8') : null })),
    rootExists: fs.existsSync(root),
    rootConfig: meta.getSyncConfig(rootId),
    unknown: fs.existsSync(path.join(root, 'unknown.txt')) ? fs.readFileSync(path.join(root, 'unknown.txt'), 'utf8') : null,
    renamed: fs.existsSync(path.join(root, 'renamed.txt')) ? fs.readFileSync(path.join(root, 'renamed.txt'), 'utf8') : null,
    allRecovery: rootReconcile || upgradeScenario || staleScenario ? tree(path.join(userData, 'sync-recovery', rootId)) : undefined,
    cursor: meta.getSyncConfig(rootId).cursor, state: meta.getSyncState(rootId, '/note.txt'),
    states: meta.getAllSyncStates(rootId),
    tree: folderScenario || conflictScenario || identityScenario || moveScenario ? tree(root) : undefined,
    oldFolderMtime: folderScenario && fs.existsSync(path.join(root, 'oldfolder')) ? fs.statSync(path.join(root, 'oldfolder')).mtimeMs : null,
    recoveryTree: folderScenario ? tree(path.join(recovery, 'files')) : undefined,
    attempts,
    outside: fs.readFileSync(path.join(outside, 'sentinel.txt'), 'utf8'),
    outsideManifest: fs.readFileSync(path.join(outside, rootId, logId, 'manifest.json.tmp'), 'utf8'),
    second: fs.existsSync(path.join(root, 'second.txt')) ? fs.readFileSync(path.join(root, 'second.txt'), 'utf8') : null,
    target: fs.existsSync(path.join(root, targetRelative.slice(1))) && fs.statSync(path.join(root, targetRelative.slice(1))).isFile()
      ? { bytes: fs.readFileSync(path.join(root, targetRelative.slice(1)), 'utf8'),
        mtime: fs.statSync(path.join(root, targetRelative.slice(1))).mtimeMs } : null,
    otherConfig: meta.getSyncConfig(otherRootId), otherState: meta.getSyncState(otherRootId, '/sentinel.txt'),
    otherStates: meta.getAllSyncStates(otherRootId),
    otherNote: fs.existsSync(path.join(other, 'note.txt')) ? fs.readFileSync(path.join(other, 'note.txt'), 'utf8') : null,
    otherBytes: fs.readFileSync(path.join(other, 'sentinel.txt'), 'utf8'),
    source: fs.existsSync(path.join(root, 'note.txt')) ? fs.readFileSync(path.join(root, 'note.txt'), 'utf8') : null,
    saved: fs.existsSync(saved) ? fs.readFileSync(saved, 'utf8') : null,
    manifest: fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, 'utf8')) : null,
    history: meta.getSyncHistory(rootId), logs, requests,
  };
  database.close();
  process.stdout.write(JSON.stringify(snapshot));
})().catch(error => { console.error(error); process.exitCode = 1; });
