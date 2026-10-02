import { createRequire } from 'node:module';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
const axios = require('axios');
const { loadWeb } = require('../../../st-desktop/src/auth-test-harness.cjs');

function loadAuth({ accessToken, refreshToken, refresh }) {
  const requests = [];
  const web = loadWeb({
    base: 'http://localhost:8080', accessToken, refreshToken,
    axiosOverride: { ...axios, post: (...args) => { requests.push(args); return refresh(...args); } },
  });
  return { ...web, store: web.loadStore(), requests };
}

function token(expiresInSeconds) {
  return `a.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expiresInSeconds, iat: Math.floor(Date.now() / 1000) - 8 * 24 * 3600 })).toString('base64url')}.c`;
}

test('仅有旧版 refresh token 时共用一次恢复，成对保存后才放行', async () => {
  let finish;
  const pending = new Promise((resolve) => { finish = resolve; });
  const auth = loadAuth({ refreshToken: 'old-refresh', refresh: () => pending });
  assert.equal(auth.store.getState().authReady, false);
  const first = auth.store.getState().restoreSession();
  const second = auth.store.getState().restoreSession();
  assert.equal(auth.requests.length, 1);
  assert.equal(auth.store.getState().authReady, false);
  finish({ data: { code: 200, data: { token: 'new-access', refreshToken: 'new-refresh' } } });
  await Promise.all([first, second]);
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, true);
  assert.equal(auth.sessionStorage.getItem('accessToken'), 'new-access');
  assert.equal(auth.localStorage.getItem('refreshToken'), 'new-refresh');
  const pair = JSON.parse(auth.localStorage.getItem('stcloud:auth'));
  assert.equal(pair.token, 'new-access');
  assert.equal(pair.refreshToken, 'new-refresh');
});

test('服务明确 401 拒绝时清除会话并拒绝进入受保护页面', async () => {
  const auth = loadAuth({ accessToken: token(-1), refreshToken: 'expired', refresh: async () => { throw { isAxiosError: true, response: { status: 401 } }; } });
  await auth.store.getState().restoreSession();
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, false);
  assert.equal(auth.sessionStorage.getItem('accessToken'), null);
  assert.equal(auth.localStorage.getItem('refreshToken'), null);
});

test('暂时断网恢复结束等待并保留 refresh，之后可以成功轮换', async () => {
  let online = false;
  const auth = loadAuth({ refreshToken: 'recoverable', refresh: async () => {
    if (!online) throw Error('Network Error');
    return { data: { code: 200, data: { token: 'online-access', refreshToken: 'online-refresh' } } };
  } });
  await auth.store.getState().restoreSession();
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, true);
  assert.equal(auth.localStorage.getItem('refreshToken'), 'recoverable');
  online = true;
  await auth.auth.refreshSession();
  assert.equal(auth.localStorage.getItem('refreshToken'), 'online-refresh');
});

test('有效 access 直接放行，临近过期的 token 等待恢复', async () => {
  const valid = loadAuth({ accessToken: token(3600), refreshToken: 'refresh', refresh: async () => { throw Error('unexpected refresh'); } });
  assert.equal(valid.store.getState().authReady, true);
  await valid.store.getState().restoreSession();
  assert.equal(valid.requests.length, 0);
  const expiring = loadAuth({ accessToken: token(10), refreshToken: 'refresh', refresh: async () => ({ data: { code: 200, data: { token: 'next', refreshToken: 'next-refresh' } } }) });
  assert.equal(expiring.store.getState().authReady, false);
  await expiring.store.getState().restoreSession();
  assert.equal(expiring.requests.length, 1);
  assert.equal(expiring.store.getState().authReady, true);
});

for (const success of [true, false]) {
  test(`启动恢复迟到${success ? '成功' : '失败'}不覆盖新登录`, async () => {
    let finish;
    let fail;
    const pending = new Promise((resolve, reject) => { finish = resolve; fail = reject; });
    const auth = loadAuth({ refreshToken: 'old-refresh', refresh: () => pending });
    const restore = auth.store.getState().restoreSession();
    const context = auth.auth.beginAuthSession();
    await auth.auth.saveLoginCredentials({ token: 'new-login', refreshToken: 'new-refresh' }, context);
    if (success) finish({ data: { code: 200, data: { token: 'old-access', refreshToken: 'old-next' } } });
    else fail({ isAxiosError: true, response: { status: 401 } });
    await restore;
    assert.equal(auth.store.getState().isAuthenticated, true);
    assert.equal(auth.auth.getCurrentAuth().token, 'new-login');
  });
}

test('没有令牌时直接显示未登录状态', () => {
  const auth = loadAuth({ refresh: async () => { throw Error('unexpected refresh'); } });
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, false);
});

test('主动定时器与同时发生的刷新共用一次 HTTP 请求', async () => {
  let finish;
  const pending = new Promise((resolve) => { finish = resolve; });
  const auth = loadAuth({ accessToken: token(3600), refreshToken: 'refresh', refresh: () => pending });
  auth.timers[0]();
  const concurrent = auth.auth.refreshSession();
  assert.equal(auth.requests.length, 1);
  finish({ data: { code: 200, data: { token: 'next', refreshToken: 'next-refresh' } } });
  await concurrent;
  assert.equal(auth.localStorage.getItem('refreshToken'), 'next-refresh');
});
