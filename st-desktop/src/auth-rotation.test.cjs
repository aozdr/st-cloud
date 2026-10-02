const assert = require('node:assert/strict');
const { test } = require('node:test');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const axios = require('axios');
const { loadDesktop, loadWeb, makeBridge } = require('./auth-test-harness.cjs');

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

async function fixture(t) {
  const state = {
    access: 'access-0', refresh: 'refresh-0', refreshCalls: 0, rotations: 0,
    refreshStatus: 200, refreshGate: null, refreshStarted: deferred(), lateGate: null, seen: [], always401: false,
    disconnectRefresh: false, refreshCode: 200,
  };
  const server = http.createServer(async (req, res) => {
    const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
    if (req.url === '/api/auth/refresh') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const input = JSON.parse(Buffer.concat(chunks).toString());
      state.refreshCalls++;
      state.refreshStarted.resolve();
      if (state.refreshGate) await state.refreshGate.promise;
      if (state.disconnectRefresh) return req.socket.destroy();
      if (state.refreshStatus !== 200) return send(state.refreshStatus, { code: state.refreshStatus });
      if (state.refreshCode !== 200) return send(200, { code: state.refreshCode });
      if (input.refreshToken !== state.refresh) return send(401, { code: 401 });
      state.rotations++;
      state.access = `access-${state.rotations}`;
      state.refresh = `refresh-${state.rotations}`;
      return send(200, { code: 200, data: { token: state.access, refreshToken: state.refresh } });
    }
    const access = req.headers.authorization;
    state.seen.push({ path: req.url, access });
    if (req.url === '/api/late' && access === 'Bearer access-0') await state.lateGate.promise;
    if (state.always401 || access !== `Bearer ${state.access}` || access === 'Bearer access-0') return send(401, { code: 401 });
    return send(200, { code: 200, data: { ok: true } });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); });
  return { state, base: `http://127.0.0.1:${server.address().port}` };
}

test('桌面连续轮换两轮并拒绝已用 refresh，状态通知包含完整令牌对', async (t) => {
  const { state, base } = await fixture(t);
  const desktop = loadDesktop(base);
  const events = [];
  desktop.onAuthChanged((auth) => events.push(auth));
  desktop.setAuth('access-0', 'refresh-0', 'session-a');
  await desktop.refreshAuth('session-a');
  await assert.rejects(axios.post(base + '/api/auth/refresh', { refreshToken: 'refresh-0' }), (error) => error.response.status === 401);
  await desktop.refreshAuth('session-a');
  assert.equal(state.rotations, 2);
  assert.equal(desktop.getAuth().refreshToken, 'refresh-2');
  assert.equal(events.at(-1).token, 'access-2');
  assert.equal(events.at(-1).refreshToken, 'refresh-2');
  // 同会话迟到的旧同步不得回滚已经轮换的 token。
  desktop.setAuth('access-0', 'refresh-0', 'session-a');
  assert.equal(desktop.getToken(), 'access-2');
});

test('主进程与 renderer 并发 401 共用一次轮换，迟到 401 使用最新 access', async (t) => {
  const { state, base } = await fixture(t);
  state.refreshGate = deferred();
  state.lateGate = deferred();
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
  await web.auth.syncDesktopAuth();
  const late = desktop.apiClient.get('/late');
  const requests = [desktop.apiClient.get('/one'), desktop.apiClient.get('/two'), web.api.get('/three'), web.api.get('/four')];
  await state.refreshStarted.promise;
  state.refreshGate.resolve();
  await Promise.all(requests);
  state.lateGate.resolve();
  await late;
  assert.equal(state.refreshCalls, 1);
  assert.equal(web.sessionStorage.getItem('accessToken'), 'access-1');
  assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-1');
});

test('真实磁盘记录重启恢复最新成对令牌，再次轮换成功', async (t) => {
  const { state, base } = await fixture(t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'st-auth-'));
  assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir()) + path.sep));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'local-storage.json');
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', file, bridge: makeBridge(desktop) });
  await web.auth.refreshSession();
  await web.auth.refreshSession();
  const restartedMain = loadDesktop(base);
  const restored = loadWeb({ base, file, bridge: makeBridge(restartedMain) });
  assert.equal(restored.sessionStorage.getItem('accessToken'), 'access-2');
  await restored.auth.refreshSession();
  assert.equal(state.rotations, 3);
  assert.equal(restored.localStorage.getItem('refreshToken'), 'refresh-3');
  const record = JSON.parse(JSON.parse(fs.readFileSync(file, 'utf8'))['stcloud:auth']);
  assert.equal(record.token, 'access-3');
  assert.equal(record.refreshToken, 'refresh-3');
});

for (const status of [401, 503]) {
  test(`刷新 HTTP ${status} 区分永久拒绝与暂时不可用，失败无重试循环`, async (t) => {
    const { state, base } = await fixture(t);
    state.refreshStatus = status;
    state.refreshGate = deferred();
    const desktop = loadDesktop(base);
    const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
    await web.auth.syncDesktopAuth();
    const requests = [desktop.apiClient.get('/one'), web.api.get('/two')];
    await state.refreshStarted.promise;
    state.refreshGate.resolve();
    const results = await Promise.allSettled(requests);
    assert.equal(results.filter((r) => r.status === 'rejected').length, 2);
    assert.equal(state.refreshCalls, 1);
    assert.equal(desktop.getAuth().refreshToken, status === 401 ? null : 'refresh-0');
    assert.equal(web.localStorage.getItem('refreshToken'), status === 401 ? null : 'refresh-0');
    if (status === 503) {
      state.refreshStatus = 200;
      await web.auth.refreshSession();
      assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-1');
    }
  });
}

for (const status of [200, 401]) {
  for (const change of ['logout', 'login', 'server']) {
    test(`迟到刷新 ${status} 不覆盖 ${change} 后的会话，也不重放旧业务`, async (t) => {
      const { state, base } = await fixture(t);
      state.refreshGate = deferred();
      state.refreshStatus = status;
      const desktop = loadDesktop(base);
      const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
      await web.auth.syncDesktopAuth();
      const pending = desktop.apiClient.get('/old-operation');
      const outcome = pending.then(() => 'fulfilled', () => 'rejected');
      await state.refreshStarted.promise;
      if (change === 'server') {
        desktop.setBaseUrl(base + '/new-server');
        web.setBase(base + '/new-server');
        web.auth.getCurrentAuth();
      } else {
        const context = web.auth.beginAuthSession();
        if (change === 'login') await web.auth.saveLoginCredentials({ token: 'new-login', refreshToken: 'new-refresh' }, context);
      }
      state.refreshGate.resolve();
      assert.equal(await outcome, 'rejected');
      assert.equal(desktop.getToken(), change === 'login' ? 'new-login' : null);
      assert.equal(web.auth.getCurrentAuth().token, change === 'login' ? 'new-login' : null);
      assert.equal(state.seen.filter((entry) => entry.path === '/api/old-operation').length, 1);
    });
  }
}

test('刷新成功但落盘失败保留内存最新对，恢复存储后同步再次落盘', async (t) => {
  const { base } = await fixture(t);
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
  await web.auth.syncDesktopAuth();
  web.localStorage.failWrites(true);
  await web.auth.refreshSession();
  assert.equal(web.auth.getCurrentAuth().refreshToken, 'refresh-1');
  assert.equal(desktop.getAuth().refreshToken, 'refresh-1');
  assert.ok(web.warnings.some((warning) => warning.includes('持久化')));
  web.localStorage.failWrites(false);
  await web.auth.syncDesktopAuth();
  assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-1');
});

test('重放后仍 401 只重试一次', async (t) => {
  const { state, base } = await fixture(t);
  state.always401 = true;
  const desktop = loadDesktop(base);
  desktop.setAuth('access-0', 'refresh-0');
  await assert.rejects(desktop.apiClient.get('/forever-401'));
  assert.equal(state.refreshCalls, 1);
  assert.equal(state.seen.length, 2);
});

test('浏览器主动与业务轮换统一，旧刷新失败不登出新登录', async (t) => {
  const { state, base } = await fixture(t);
  state.refreshGate = deferred();
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0' });
  const business = web.api.get('/one');
  const outcome = business.then(() => 'fulfilled', () => 'rejected');
  await state.refreshStarted.promise;
  const proactive = web.auth.refreshSession();
  const proactiveOutcome = proactive.then(() => 'fulfilled', () => 'rejected');
  const context = web.auth.beginAuthSession();
  await web.auth.saveLoginCredentials({ token: 'new-login', refreshToken: 'new-refresh' }, context);
  state.refreshStatus = 401;
  state.refreshGate.resolve();
  assert.equal(await outcome, 'rejected');
  assert.equal(await proactiveOutcome, 'rejected');
  assert.equal(web.auth.getCurrentAuth().token, 'new-login');
  assert.equal(state.refreshCalls, 1);
});

test('实际连接中断保留会话，网络恢复后允许下一次轮换', async (t) => {
  const { state, base } = await fixture(t);
  state.disconnectRefresh = true;
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
  await assert.rejects(web.auth.refreshSession());
  assert.equal(desktop.getAuth().refreshToken, 'refresh-0');
  assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-0');
  state.disconnectRefresh = false;
  await web.auth.refreshSession();
  assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-1');
});

test('迟到旧修订/旧会话 IPC 事件不回滚当前持久令牌', async (t) => {
  const { base } = await fixture(t);
  const desktop = loadDesktop(base);
  let listener;
  const bridge = { ...makeBridge(desktop), onAuthChanged: (callback) => { listener = callback; return desktop.onAuthChanged(callback); } };
  const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge });
  await web.auth.syncDesktopAuth();
  const old = desktop.getAuth();
  await web.auth.refreshSession();
  listener(old);
  assert.equal(web.localStorage.getItem('refreshToken'), 'refresh-1');
  const context = web.auth.beginAuthSession();
  await web.auth.saveLoginCredentials({ token: 'new-login', refreshToken: 'new-refresh' }, context);
  listener({ ...old, token: null, refreshToken: null, revision: 999 });
  assert.equal(web.auth.getCurrentAuth().token, 'new-login');
});

test('仅有旧版 refresh 的桌面安装通过主进程恢复，不产生重复轮换', async (t) => {
  const { state, base } = await fixture(t);
  const desktop = loadDesktop(base);
  const web = loadWeb({ base, refreshToken: 'refresh-0', bridge: makeBridge(desktop) });
  const store = web.loadStore();
  assert.equal(store.getState().authReady, false);
  await Promise.all([store.getState().restoreSession(), store.getState().restoreSession()]);
  assert.equal(state.refreshCalls, 1);
  assert.equal(store.getState().isAuthenticated, true);
  assert.equal(web.auth.getCurrentAuth().token, 'access-1');
});

for (const code of [1002, 1004, 1005, 5001]) {
  for (const electron of [true, false]) {
    test(`${electron ? '桌面' : '浏览器'} HTTP 200 业务码 ${code} 正确区分令牌拒绝与暂时不可用`, async (t) => {
      const { state, base } = await fixture(t);
      state.refreshCode = code;
      const desktop = electron ? loadDesktop(base) : null;
      const web = loadWeb({ base, accessToken: 'access-0', refreshToken: 'refresh-0', bridge: desktop ? makeBridge(desktop) : undefined });
      await assert.rejects(web.auth.refreshSession());
      assert.equal(web.auth.getCurrentAuth().refreshToken, code === 5001 ? 'refresh-0' : null);
      assert.equal(state.refreshCalls, 1);
    });
  }
}
