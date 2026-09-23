import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const ts = require('typescript');
const source = readFileSync(resolve('src/store/auth.ts'), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;

function storage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

function loadAuth({ accessToken, refreshToken, refresh }) {
  const sessionStorage = storage(accessToken ? { accessToken } : {});
  const localStorage = storage(refreshToken ? { refreshToken } : {});
  const requests = [];
  let syncCount = 0;
  const axios = {
    post: (...args) => {
      requests.push(args);
      return refresh(...args);
    },
  };
  const create = (initializer) => {
    let state;
    const store = () => state;
    store.getState = () => state;
    const set = (partial) => { state = { ...state, ...partial }; };
    state = initializer(set);
    return store;
  };
  const modules = {
    zustand: { create },
    axios: { __esModule: true, default: axios },
    '../lib/api': { __esModule: true, default: { post: async () => ({}) } },
    '../lib/electron': { syncAuthToElectron: () => { syncCount++; } },
    '../lib/server-config': { getApiBaseUrl: () => 'http://localhost/api' },
    './favorites': { useFavoritesStore: { getState: () => ({ reset: () => {} }) } },
  };
  const exports = {};
  const context = {
    exports,
    require: (name) => modules[name] ?? require(name),
    sessionStorage,
    localStorage,
    atob,
    setInterval: () => 1,
    clearInterval: () => {},
    window: { location: {} },
  };
  vm.runInNewContext(compiled, context, { filename: 'auth.ts' });
  return { store: exports.useAuthStore, sessionStorage, localStorage, requests, syncCount: () => syncCount };
}

function token(expiresInSeconds) {
  return `a.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expiresInSeconds })).toString('base64url')}.c`;
}

test('仅有 refresh token 时只刷新一次，成功后才放行', async () => {
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
  assert.equal(auth.syncCount(), 1);
});

test('刷新失败时清理令牌并拒绝进入受保护页面', async () => {
  const auth = loadAuth({ accessToken: token(-1), refreshToken: 'expired', refresh: async () => { throw Error('401'); } });
  await auth.store.getState().restoreSession();
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, false);
  assert.equal(auth.sessionStorage.getItem('accessToken'), null);
  assert.equal(auth.localStorage.getItem('refreshToken'), null);
});

test('有效 access token 直接放行，临近过期的 token 等待恢复', async () => {
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

test('没有令牌时直接显示未登录状态', () => {
  const auth = loadAuth({ refresh: async () => { throw Error('unexpected refresh'); } });
  assert.equal(auth.store.getState().authReady, true);
  assert.equal(auth.store.getState().isAuthenticated, false);
});
