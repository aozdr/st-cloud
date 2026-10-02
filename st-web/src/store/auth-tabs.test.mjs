import { createRequire } from 'node:module';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

const require = createRequire(import.meta.url);
const axios = require('axios');
const { loadWeb, sharedStorage, webLocks } = require('../../../st-desktop/src/auth-test-harness.cjs');
const STORAGE_KEY = 'stcloud:auth';

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

async function fixture(t, mode = 'tickets') {
  const state = { refresh: 'refresh-0', access: 'access-0', calls: [], rotations: 0, rejections: 0, status: 200, gate: null, started: deferred() };
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const input = JSON.parse(Buffer.concat(chunks).toString());
    state.calls.push(input.refreshToken);
    const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
    if (state.status !== 200) return send(state.status, { code: state.status });
    if (input.refreshToken !== state.refresh) {
      state.rejections++;
      return send(401, { code: 401 });
    }
    // 模拟服务端 CAS 已提交、成功 HTTP 响应尚未到客户端的危险窗口。
    state.rotations++;
    state.access = `access-${state.rotations}`;
    state.refresh = `refresh-${state.rotations}`;
    const pair = { token: state.access, refreshToken: state.refresh };
    state.started.resolve();
    if (state.gate) await state.gate.promise;
    return send(200, { code: 200, data: pair });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { state.gate?.resolve(); server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const initial = { token: state.access, refreshToken: state.refresh, sessionId: 'shared-session', serverUrl: base, revision: 0 };
  const hub = sharedStorage({ [STORAGE_KEY]: JSON.stringify(initial), refreshToken: initial.refreshToken });
  const locks = mode === 'locks' ? webLocks() : undefined;
  const open = (extra = {}) => loadWeb({ base, storageHub: hub, locks, ...extra });
  return { state, base, initial, hub, open, a: open(), b: open() };
}

function assertPair(web, access, refresh, persisted = true) {
  assert.equal(web.auth.getCurrentAuth().token, access);
  assert.equal(web.auth.getCurrentAuth().refreshToken, refresh);
  if (persisted) assert.equal(web.sessionStorage.getItem('accessToken'), access);
}

for (const mode of ['locks', 'tickets']) {
  test(`${mode}: 事件未送达时顺序标签页读取完整最新对后再次轮换`, async (t) => {
    const { state, hub, a, b } = await fixture(t, mode);
    hub.pauseEvents();
    await a.auth.refreshSession();
    await b.auth.refreshSession();
    assert.deepEqual(state.calls, ['refresh-0', 'refresh-1']);
    assert.equal(state.rejections, 0);
    assertPair(a, 'access-2', 'refresh-2');
    assertPair(b, 'access-2', 'refresh-2');
  });

  test(`${mode}: CAS 成功但响应延迟时并发标签页排队并复用成功对`, async (t) => {
    const { state, hub, a, b } = await fixture(t, mode);
    hub.pauseEvents();
    state.gate = deferred();
    const first = a.auth.refreshSession();
    await state.started.promise;
    const second = b.auth.refreshSession();
    await delay(65);
    assert.deepEqual(state.calls, ['refresh-0']);
    assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
    state.gate.resolve();
    const [one, two] = await Promise.all([first, second]);
    assert.equal(one.token, 'access-1');
    assert.equal(two.token, one.token);
    assert.equal(two.refreshToken, one.refreshToken);
    assert.equal(state.rotations, 1);
    assert.equal(state.rejections, 0);
    assertPair(a, 'access-1', 'refresh-1');
    assertPair(b, 'access-1', 'refresh-1');
  });
}

test('无 Web Locks：另一页在选号写入途中进入，仍按 bakery 顺序仅提交一次 refresh', async (t) => {
  const { state, hub, a, b } = await fixture(t);
  hub.pauseEvents();
  state.gate = deferred();
  const originalSet = hub.storage.setItem;
  let second;
  let interleaved = false;
  hub.storage.setItem = (key, value) => {
    originalSet(key, value);
    if (!interleaved && key.startsWith(STORAGE_KEY + ':refresh:') && JSON.parse(value).choosing) {
      interleaved = true;
      second = b.auth.refreshSession();
    }
  };
  const first = a.auth.refreshSession();
  await state.started.promise;
  await delay(65);
  assert.equal(interleaved, true);
  assert.deepEqual(state.calls, ['refresh-0']);
  state.gate.resolve();
  const [one, two] = await Promise.all([first, second]);
  assert.equal(one.refreshToken, 'refresh-1');
  assert.equal(two.refreshToken, 'refresh-1');
  assert.equal(state.rejections, 0);
});

test('无 Web Locks：租约失效后的拒绝先返回时不清除仍在途的成功会话', async (t) => {
  const { state, hub, a, b } = await fixture(t);
  hub.pauseEvents();
  state.gate = deferred();
  const first = a.auth.refreshSession();
  await state.started.promise;
  const key = Array.from({ length: hub.storage.length }, (_, index) => hub.storage.key(index)).find((entry) => entry.startsWith(STORAGE_KEY + ':refresh:'));
  const lease = JSON.parse(hub.storage.getItem(key));
  hub.storage.setItem(key, JSON.stringify({ ...lease, expiresAt: Date.now() - 1 }));
  await assert.rejects(b.auth.refreshSession());
  assert.equal(state.rejections, 1);
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
  assert.equal(b.auth.getCurrentAuth().refreshToken, 'refresh-0');
  state.gate.resolve();
  await first;
  assertPair(b, 'access-1', 'refresh-1');
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-1');
});

test('storage 退出传播清空 sessionStorage 和认证订阅状态', async (t) => {
  const { a, b } = await fixture(t);
  const store = b.loadStore();
  store.setState({ user: { id: 'old-user' } });
  const oldContext = b.auth.captureAuthContext();
  a.auth.beginAuthSession();
  await delay(0);
  assert.equal(store.getState().isAuthenticated, false);
  assert.equal(store.getState().user, null);
  assert.equal(b.sessionStorage.getItem('accessToken'), null);
  assert.equal(b.auth.isCurrentAuth(oldContext), false);
});

test('合并/迟到的换账号 storage 事件清空旧资料，旧事件不能回滚当前对', async (t) => {
  const { hub, initial, a, b } = await fixture(t);
  const store = b.loadStore();
  store.setState({ user: { id: 'old-user' } });
  hub.pauseEvents();
  const oldContext = b.auth.captureAuthContext();
  const next = a.auth.beginAuthSession();
  await a.auth.saveLoginCredentials({ token: 'other-access', refreshToken: 'other-refresh' }, next);
  hub.flushEvents();
  assertPair(b, 'other-access', 'other-refresh');
  assert.equal(store.getState().user, null);
  assert.equal(store.getState().isAuthenticated, true);
  assert.equal(b.auth.isCurrentAuth(oldContext), false);
  b.emitStorage({ key: STORAGE_KEY, newValue: JSON.stringify(initial), oldValue: hub.storage.getItem(STORAGE_KEY), storageArea: b.localStorage });
  b.emitStorage({ key: STORAGE_KEY, newValue: null, storageArea: b.localStorage });
  assertPair(b, 'other-access', 'other-refresh');
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).token, 'other-access');
});

test('同会话迟到低修订 storage 记录不回滚内存中的最新完整对', async (t) => {
  const { hub, initial, a, b } = await fixture(t);
  await a.auth.refreshSession();
  assertPair(b, 'access-1', 'refresh-1');
  hub.storage.setItem(STORAGE_KEY, JSON.stringify(initial));
  b.emitStorage({ key: STORAGE_KEY, newValue: JSON.stringify(initial), storageArea: b.localStorage });
  assertPair(b, 'access-1', 'refresh-1');
});

for (const outcome of ['success', 'rejection']) {
  test(`旧会话在途 ${outcome} 事件未送达时也不能覆盖或清除其它页新登录`, async (t) => {
    const { hub, a, b } = await fixture(t);
    hub.pauseEvents();
    const gate = deferred();
    const old = loadWeb({ base: a.auth.getCurrentAuth().serverUrl, storageHub: hub, axiosOverride: {
      ...axios, post: async () => { await gate.promise;
        if (outcome === 'rejection') throw { isAxiosError: true, response: { status: 401 } };
        return { data: { code: 200, data: { token: 'stale-access', refreshToken: 'stale-refresh' } } };
      },
    } });
    const pending = old.auth.refreshSession();
    const result = pending.then(() => 'fulfilled', () => 'rejected');
    const next = b.auth.beginAuthSession();
    await b.auth.saveLoginCredentials({ token: 'new-access', refreshToken: 'new-refresh' }, next);
    gate.resolve();
    assert.equal(await result, 'rejected');
    assertPair(old, 'new-access', 'new-refresh');
    assertPair(a, 'new-access', 'new-refresh');
    assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'new-refresh');
  });
}

test('无 Web Locks：503 保留共享会话，网络恢复后重新排队并成功', async (t) => {
  const { state, hub, a, b } = await fixture(t);
  state.status = 503;
  await assert.rejects(a.auth.refreshSession());
  assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-0');
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
  state.status = 200;
  await b.auth.refreshSession();
  assertPair(a, 'access-1', 'refresh-1');
});

test('浏览器持久化失败保留内存最新对，恢复写入后下一轮保存完整对', async (t) => {
  const { state, hub, a } = await fixture(t);
  const write = a.localStorage.setItem;
  let failPair = true;
  a.localStorage.setItem = (key, value) => {
    if (key === STORAGE_KEY && failPair) throw new Error('pair write unavailable');
    return write(key, value);
  };
  await a.auth.refreshSession();
  assert.equal(a.auth.getCurrentAuth().token, 'access-1');
  assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-1');
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
  assert.ok(a.warnings.some((entry) => entry.includes('持久化')));
  failPair = false;
  await a.auth.refreshSession();
  assert.equal(state.rotations, 2);
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-2');
});

test('协调存储不可写时不消费令牌，恢复后可重新续期', async (t) => {
  const { state, hub, a } = await fixture(t);
  state.status = 401;
  hub.storage.failWrites(true);
  await assert.rejects(a.auth.refreshSession());
  assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-0');
  assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
  assert.deepEqual(state.calls, []);
  hub.storage.failWrites(false);
  state.status = 200;
  await a.auth.refreshSession();
  assertPair(a, 'access-1', 'refresh-1');
});

for (const mode of ['locks', 'tickets']) {
  for (const change of ['rotation', 'logout']) {
    test(`${mode}: 启动采用旧记录后另一页${change}不造成共享基线错位`, async (t) => {
      const { state, hub, initial, open } = await fixture(t, mode);
      const connect = hub.connect;
      let injected = false;
      hub.connect = (deliver) => {
        const storage = connect(deliver);
        const write = storage.setItem;
        storage.setItem = (key, value) => {
          write(key, value);
          if (key !== 'refreshToken' || injected) return;
          // 另一浏览器进程可在新页首次读取与镜像写入之间完成续期/退出。
          injected = true;
          if (change === 'rotation') {
            state.access = 'access-1'; state.refresh = 'refresh-1'; state.rotations = 1;
            hub.storage.setItem(STORAGE_KEY, JSON.stringify({ ...initial, token: state.access,
              refreshToken: state.refresh, revision: 1 }));
          } else {
            hub.storage.removeItem(STORAGE_KEY);
            hub.storage.removeItem('refreshToken');
          }
        };
        return storage;
      };
      const started = open();
      assert.equal(injected, true);
      if (change === 'rotation') {
        assertPair(started, 'access-1', 'refresh-1');
        await started.auth.refreshSession();
        assert.deepEqual(state.calls, ['refresh-1']);
        assertPair(started, 'access-2', 'refresh-2');
      } else {
        assert.equal(started.auth.getCurrentAuth().refreshToken, null);
        assert.equal(started.sessionStorage.getItem('accessToken'), null);
        await assert.rejects(started.auth.refreshSession());
        assert.deepEqual(state.calls, []);
      }
    });
  }

  test(`${mode}: 成功对写入失败时另一页拒绝不清除成功内存，保存恢复后共享最新对`, async (t) => {
    const { state, hub, a, b } = await fixture(t, mode);
    const write = a.localStorage.setItem;
    let failOnce = true;
    a.localStorage.setItem = (key, value) => {
      if (key === STORAGE_KEY && failOnce) {
        failOnce = false;
        throw new Error('pair write unavailable');
      }
      return write(key, value);
    };
    await a.auth.refreshSession();
    assertPair(a, 'access-1', 'refresh-1', false);
    await assert.rejects(b.auth.refreshSession());
    assert.equal(state.rejections, 1);
    assertPair(a, 'access-1', 'refresh-1', false);
    assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
    const markers = Array.from({ length: hub.storage.length }, (_, i) => hub.storage.key(i))
      .filter((key) => key.startsWith(STORAGE_KEY + ':rotation:'));
    assert.equal(markers.length, 1);
    const marker = JSON.parse(hub.storage.getItem(markers[0]));
    assert.equal('token' in marker || 'refreshToken' in marker, false);
    await a.auth.refreshSession();
    assertPair(b, 'access-2', 'refresh-2');
    assert.deepEqual(state.calls, ['refresh-0', 'refresh-0', 'refresh-1']);
    assert.equal(Array.from({ length: hub.storage.length }, (_, i) => hub.storage.key(i))
      .some((key) => key.startsWith(STORAGE_KEY + ':rotation:')), false);
  });
}

test('Web Locks：协调标记不可写时不消费令牌，恢复后仍可续期', async (t) => {
  const { state, hub, a } = await fixture(t, 'locks');
  hub.storage.failWrites(true);
  await assert.rejects(a.auth.refreshSession());
  assert.deepEqual(state.calls, []);
  assert.equal(state.refresh, 'refresh-0');
  assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-0');
  hub.storage.failWrites(false);
  await a.auth.refreshSession();
  assertPair(a, 'access-1', 'refresh-1');
});

for (const mode of ['locks', 'tickets']) {
  test(`${mode}: 拒绝清理的 marker 扫描期间完成的新登录不会被旧请求删除`, async (t) => {
    const { state, hub, a, b } = await fixture(t, mode);
    state.status = 401;
    const read = a.localStorage.getItem;
    let injected = false;
    let login;
    a.localStorage.getItem = (key) => {
      const value = read(key);
      if (!injected && key.startsWith(STORAGE_KEY + ':rotation:')) {
        injected = true;
        // 服务已拒绝旧 refresh；另一个浏览器进程在清理检查的原子读之间完成换账号。
        const context = b.auth.beginAuthSession();
        login = b.auth.saveLoginCredentials({ token: 'new-access', refreshToken: 'new-refresh' }, context);
      }
      return value;
    };
    await assert.rejects(a.auth.refreshSession());
    await login;
    assert.equal(injected, true);
    assert.deepEqual(state.calls, ['refresh-0']);
    assertPair(a, 'new-access', 'new-refresh');
    assertPair(b, 'new-access', 'new-refresh');
    assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'new-refresh');
  });

  test(`${mode}: 普通永久拒绝失效旧修订并传播，重载不恢复已拒绝记录`, async (t) => {
    const { state, hub, a, b, open } = await fixture(t, mode);
    state.status = 401;
    await assert.rejects(a.auth.refreshSession());
    assert.equal(a.auth.getCurrentAuth().refreshToken, null);
    assert.equal(b.auth.getCurrentAuth().refreshToken, null);
    assert.equal(a.sessionStorage.getItem('accessToken'), null);
    assert.equal(hub.storage.getItem('refreshToken'), null);
    // 旧完整记录保留但对应修订有拒绝标记，重载不能重新接受它。
    assert.equal(open().auth.getCurrentAuth().refreshToken, null);
    const next = b.auth.beginAuthSession();
    await b.auth.saveLoginCredentials({ token: 'new-access', refreshToken: 'new-refresh' }, next);
    assertPair(a, 'new-access', 'new-refresh');
    assertPair(open(), 'new-access', 'new-refresh');
  });
}

for (const mode of ['locks', 'tickets']) {
  test(`${mode}: 新对未保存后该当前修订明确拒绝，应失效本页并阻止更早共享对重载`, async (t) => {
    const { state, hub, a, b, open } = await fixture(t, mode);
    const store = a.loadStore();
    store.setState({ user: { id: 'cloud-user' } });
    const write = a.localStorage.setItem;
    let failOnce = true;
    a.localStorage.setItem = (key, value) => {
      if (key === STORAGE_KEY && failOnce) { failOnce = false; throw new Error('pair unavailable'); }
      return write(key, value);
    };
    await a.auth.refreshSession();
    assert.equal(a.auth.getCurrentAuth().refreshToken, 'refresh-1');
    assert.equal(JSON.parse(hub.storage.getItem(STORAGE_KEY)).refreshToken, 'refresh-0');
    const context = a.auth.captureAuthContext();
    state.status = 401;
    await assert.rejects(a.auth.refreshSession());
    assert.deepEqual(state.calls, ['refresh-0', 'refresh-1']);
    assert.equal(a.auth.getCurrentAuth().refreshToken, null);
    assert.equal(a.auth.isCurrentAuth(context), false);
    assert.equal(store.getState().isAuthenticated, false);
    assert.equal(store.getState().user, null);
    assert.equal(b.auth.getCurrentAuth().refreshToken, null);
    assert.equal(open().auth.getCurrentAuth().refreshToken, null);
  });
}
