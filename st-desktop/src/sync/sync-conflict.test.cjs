const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { Readable } = require('node:stream');
const ts = require('typescript');

function load(file, dependencies, globals = {}) {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  const exports = {};
  vm.runInNewContext(code, { exports, console: { error() {} }, ...globals, require(name) {
    if (!(name in dependencies)) throw Error(`Unexpected dependency: ${name}`);
    return dependencies[name];
  } });
  return exports;
}

for (const taskStatus of ['failed', 'running']) {
  test(`TC04-24/25: 真实上传模块对任务 ${taskStatus} 抛错并保留旧基线`, async t => {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-upload-test-'));
    t.after(() => {
      assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
      fs.rmSync(temporary, { recursive: true, force: true });
    });
    const source = path.join(temporary, 'note.txt');
    fs.writeFileSync(source, 'local-v2');
    const state = { nodeId: '9007199254740993', md5: 'old-md5', localMtime: 1, status: 'synced', failCount: 0 };
    const writes = [];
    let clock = 0;
    const MockDate = class extends Date { static now() { clock += 600_001; return clock; } };
    const module = load('sync-upload.ts', {
      fs, path, '../api-client': { apiClient: {} },
      '../upload-manager': { startUpload: async () => 'task-1' },
      '../database': {
        getTask: () => ({ status: taskStatus, error: taskStatus === 'failed' ? 'server failed' : null }),
        getSyncState: () => state,
        upsertSyncState: value => writes.push(value),
        insertSyncHistory() {}, setBlockHashes() {}, deleteBlockHashes() {},
      },
      '../utils/md5': { calculateFileMd5: async () => '' },
      '../sync-retry': { computeBackoffMs: () => 1000 },
      '../utils/block-hash': { BLOCK_SIZE: 5 * 1024 * 1024 },
      './sync-shared': { BLOCK_SYNC_THRESHOLD: 8 * 1024 * 1024, syncLog() {}, emitSyncEvent() {} },
    }, { Date: MockDate });
    await assert.rejects(module.uploadFile({ root: { rootId: 'r', cloudFolderNodeId: 'folder' } }, source, '/note.txt', state.nodeId), /上传未完成/);
    assert.equal(fs.readFileSync(source, 'utf8'), 'local-v2');
    assert.equal(writes.length, 1);
    assert.equal(writes[0].md5, 'old-md5');
    assert.equal(writes[0].nodeId, state.nodeId);
    assert.equal(writes[0].failCount, 1);
  });
}

function fixture(t, strategy = 'keep_both') {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-conflict-test-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const root = path.join(temporary, 'root');
  fs.mkdirSync(root);
  const absPath = path.join(root, 'note.txt');
  fs.writeFileSync(absPath, 'local-v2');
  const writes = [];
  const history = [];
  const uploadCalls = [];
  let uploadError = null;
  const syncUtils = load('../sync-utils.ts', {});
  const module = load('sync-download.ts', {
    fs, os: { tmpdir: () => temporary }, path, crypto: require('node:crypto'),
    '../api-client': { apiClient: { get: async () => ({ data: Readable.from(['cloud-v2']) }) } },
    '../database': {
      getSyncState: () => ({ nodeId: '9007199254740993', md5: 'old-md5' }),
      getAllSyncStates: () => [],
      upsertSyncState: value => writes.push(value),
      insertSyncHistory: value => history.push(value),
    },
    '../utils/md5': { calculateFileMd5: async source => require('node:crypto').createHash('md5').update(fs.readFileSync(source)).digest('hex') },
    '../sync-utils': syncUtils,
    './sync-shared': { withRetry: fn => fn(), syncLog() {}, emitSyncEvent() {} },
  });
  const ctx = {
    root: { rootId: 'root-1', localPath: root }, conflictStrategy: strategy,
    markEngineWritten() {},
    async uploadFile(source, relPath, nodeId) {
      uploadCalls.push({ source, relPath, nodeId, bytes: fs.readFileSync(source, 'utf8') });
      if (uploadError) throw uploadError;
    },
  };
  const item = { nodeId: '9007199254740995', md5: require('node:crypto').createHash('md5').update('cloud-v2').digest('hex'), updatedAt: '2020-01-01T00:00:00Z' };
  return { temporary, root, absPath, writes, history, uploadCalls, ctx, item,
    failWith: error => { uploadError = error; },
    run: () => module.handleConflict(ctx, absPath, '/note.txt', item),
  };
}

for (const reason of ['failed', 'timeout']) {
  test(`TC04-24: keep_both 上传 ${reason} 后保留临时源及旧基线`, async t => {
    const f = fixture(t);
    f.failWith(Error(reason));
    await assert.rejects(f.run(), /冲突副本未完整保存/);
    assert.equal(fs.readFileSync(f.absPath, 'utf8'), 'local-v2');
    assert.equal(f.uploadCalls.length, 1);
    assert.equal(fs.readFileSync(f.uploadCalls[0].source, 'utf8'), 'local-v2');
    assert.equal(f.writes.some(w => w.localPath === '/note.txt'), false);
    assert.equal(f.history.at(-1).status, 'error');
    assert.equal(f.writes.some(w => w.nodeId === f.item.nodeId && w.status === 'conflict'), true);
  });
}

for (const strategy of ['local_wins', 'latest_wins']) {
  test(`TC04-25: ${strategy} 上传失败传播且恢复后源文件不变`, async t => {
    const f = fixture(t, strategy);
    f.failWith(Error('task timeout'));
    await assert.rejects(f.run(), /task timeout/);
    assert.equal(fs.readFileSync(f.absPath, 'utf8'), 'local-v2');
    assert.equal(f.writes.length, 0);
    f.failWith(null);
    await f.run();
    assert.equal(f.uploadCalls.length, 2);
    assert.deepEqual(f.uploadCalls.map(call => call.bytes), ['local-v2', 'local-v2']);
    assert.equal(f.uploadCalls[1].nodeId, '9007199254740993');
  });
}
