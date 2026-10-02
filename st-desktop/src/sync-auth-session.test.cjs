const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const { loadDesktop } = require('./auth-test-harness.cjs');

const jwt = id => 'x.' + Buffer.from(JSON.stringify({ userId: id })).toString('base64url') + '.x';
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

function harness(pauseAt) {
  const auth = loadDesktop('http://test');
  auth.setAuth(jwt('1'), 'refreshA', 'A');
  const entered = deferred(), release = deferred();
  const requests = [], writes = [], started = [];
  auth.apiClient.defaults.adapter = async config => {
    requests.push({ url: config.url, token: config.headers.Authorization });
    if (config.url === pauseAt) { entered.resolve(); await release.promise; }
    return { status: 200, headers: {}, config, data: { data: config.url === '/sync/roots'
      ? [{ id: 'rootA', cloudFolderNodeId: 'folderA' }, { id: 'orphanA', localPathHint: 'C:/orphan' }] : [] } };
  };
  const database = new Proxy({ getAllSyncConfigs: () => [{ rootId: 'rootA', localPath: 'C:/A', status: 'active' }],
    claimLegacySyncConfigs: () => {}, upsertSyncConfig: row => writes.push(row), deleteSyncConfig: id => writes.push(id) },
    { get: (o, k) => o[k] || (() => {}) });
  const exports = {};
  const source = fs.readFileSync(require.resolve('./sync-manager.ts'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, {
    exports, console: { log() {}, warn() {}, error() {} }, require: name => {
      if (name === './api-client') return auth;
      if (name === './database') return database;
      if (name === 'electron') return { BrowserWindow: { getAllWindows: () => [] } };
      if (name === './sync-engine') return { SyncEngine: class {
        constructor(root) { this.root = root; }
        setExclusions() {}
        async start() { started.push({ root: this.root.rootId, user: auth.getUserId() }); }
        async stop() {}
      } };
      if (name === './ws-client') return { SyncWsClient: class { onChange() {} onStatus() {} start() {} stop() {} } };
      throw Error(name);
    },
  });
  return { auth, manager: exports, entered, release, requests, writes, started };
}

for (const pauseAt of ['/sync/roots', '/sync/roots/rootA/exclusions']) {
  for (const change of ['login', 'logout', 'server']) {
    test(`${pauseAt}: ${change} cancels stale restore before further requests or writes`, async () => {
      const h = harness(pauseAt);
      const restore = h.manager.resumeSyncEngines();
      await h.entered.promise;
      const count = h.requests.length;
      if (change === 'login') h.auth.setAuth(jwt('2'), 'refreshB', 'B');
      if (change === 'logout') h.auth.clearAuth();
      if (change === 'server') h.auth.setBaseUrl('http://other');
      h.release.resolve();
      await restore;
      assert.equal(h.requests.length, count);
      assert.deepEqual(h.started, []);
      assert.deepEqual(h.writes, []);
    });
  }
}

test('a later request in the old async scope cannot borrow new credentials', async () => {
  const h = harness('unused');
  const gate = deferred();
  const operation = h.auth.runWithAuthGeneration(h.auth.captureAuthGeneration(), async () => {
    await gate.promise;
    await h.auth.apiClient.get('/file/list');
  });
  h.auth.setAuth(jwt('2'), 'refreshB', 'B');
  gate.resolve();
  await assert.rejects(operation, /认证会话已变更/);
  assert.equal(h.requests.length, 0);
});

test('unchanged session completes recovery and relinks roots', async () => {
  const h = harness('/sync/roots');
  const restore = h.manager.resumeSyncEngines();
  await h.entered.promise;
  h.release.resolve();
  await restore;
  assert.equal(h.started.length, 2);
  assert.ok(h.started.every(item => item.user === '1'));
  assert.equal(h.writes.length, 1);
});

test('normal token rotation keeps the operation generation valid', async () => {
  const axios = require('axios');
  const original = axios.post;
  const h = harness('unused');
  const generation = h.auth.captureAuthGeneration();
  axios.post = async () => ({data:{code:200,data:{token:jwt('1'),refreshToken:'rotated'}}});
  try {
    await h.auth.runWithAuthGeneration(generation, async () => {
      await h.auth.refreshAuth('A');
      h.auth.assertAuthGeneration(generation);
      await h.auth.apiClient.get('/file/list');
    });
    assert.equal(h.requests.length, 1);
    assert.equal(h.auth.getAuth().refreshToken, 'rotated');
  } finally { axios.post = original; }
});
