const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const axios = require('axios');

const repo = path.resolve(__dirname, '../..');

function memoryStorage(initial = {}, file) {
  let values = file && fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : { ...initial };
  let fail = false;
  const flush = () => { if (fail) throw new Error('disk unavailable'); if (file) fs.writeFileSync(file, JSON.stringify(values)); };
  return {
    get length() { return Object.keys(values).length; },
    key: (index) => Object.keys(values)[index] ?? null,
    getItem: (key) => values[key] ?? null,
    setItem: (key, value) => { if (fail) throw new Error('disk unavailable'); values[key] = String(value); flush(); },
    removeItem: (key) => { if (fail) throw new Error('disk unavailable'); delete values[key]; flush(); },
    failWrites: (value) => { fail = value; },
  };
}

function sharedStorage(initial = {}) {
  const storage = memoryStorage(initial);
  const connections = new Set();
  const queued = [];
  let automatic = true;
  const flushEvents = () => {
    for (const event of queued.splice(0)) {
      for (const connection of connections) {
        if (connection === event.source) continue;
        connection.deliver({ key: event.key, oldValue: event.oldValue, newValue: event.newValue, storageArea: connection.localStorage });
      }
    }
  };
  return {
    storage,
    pauseEvents: () => { automatic = false; },
    flushEvents,
    connect: (deliver) => {
      const connection = { deliver };
      const publish = (key, value) => {
        const oldValue = storage.getItem(key);
        if (value === null) storage.removeItem(key);
        else storage.setItem(key, value);
        if (oldValue !== value) {
          queued.push({ source: connection, key, oldValue, newValue: value });
          if (automatic) queueMicrotask(flushEvents);
        }
      };
      connection.localStorage = {
        get length() { return storage.length; }, key: storage.key, getItem: storage.getItem,
        setItem: (key, value) => publish(key, String(value)), removeItem: (key) => publish(key, null),
        failWrites: storage.failWrites,
      };
      connections.add(connection);
      return connection.localStorage;
    },
  };
}

function webLocks() {
  const active = new Map();
  return {
    request: (name, action) => {
      const previous = active.get(name);
      const promise = previous ? previous.catch(() => {}).then(action) : Promise.resolve(action());
      active.set(name, promise);
      return promise.finally(() => { if (active.get(name) === promise) active.delete(name); });
    },
  };
}

function compile(relative) {
  const source = fs.readFileSync(path.join(repo, relative), 'utf8').replaceAll('import.meta.env.DEV', 'false');
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText;
}

function loadDesktop(base) {
  const exports = {};
  vm.runInNewContext(compile('st-desktop/src/api-client.ts'), {
    exports,
    require: (name) => name === './server-config' ? { getServerUrl: () => base } : require(name),
    process: { env: {} }, Buffer, console,
  }, { filename: 'desktop-api-client.ts' });
  return exports;
}

function makeBridge(desktop) {
  return {
    isElectron: true,
    setAuth: (...args) => Promise.resolve(desktop.setAuth(...args)),
    getAuth: () => Promise.resolve(desktop.getAuth()),
    clearAuth: (...args) => Promise.resolve(desktop.clearAuth(...args)),
    refreshAuth: (...args) => desktop.refreshAuth(...args),
    onAuthChanged: (...args) => desktop.onAuthChanged(...args),
  };
}

function loadWeb({ base, accessToken, refreshToken, file, bridge, axiosOverride, storageHub, locks } = {}) {
  let currentBase = base;
  const handlers = new Map();
  const emitStorage = (event) => { for (const handler of handlers.get('storage') || []) handler(event); };
  const localStorage = storageHub ? storageHub.connect(emitStorage) : memoryStorage(refreshToken ? { refreshToken } : {}, file);
  const sessionStorage = memoryStorage(accessToken ? { accessToken } : {});
  const timers = [];
  const warnings = [];
  const serverConfig = { getApiBaseUrl: () => currentBase + '/api', getServerUrlSync: () => currentBase };
  const cache = new Map();
  const modules = {
    axios: axiosOverride || axios,
    './lib/server-config': serverConfig,
    './server-config': serverConfig,
    '../lib/server-config': serverConfig,
    './electron': { isElectron: () => !!bridge },
    './favorites': { useFavoritesStore: { getState: () => ({ reset: () => {} }) } },
    zustand: {
      create: (initializer) => {
        let state;
        const store = () => state;
        store.getState = () => state;
        store.setState = (partial) => { state = { ...state, ...partial }; };
        state = initializer(store.setState);
        return store;
      },
    },
  };
  const context = vm.createContext({
    sessionStorage, localStorage, atob, crypto: require('node:crypto').webcrypto,
    console: { ...console, warn: (...args) => warnings.push(args.join(' ')) },
    setInterval: (fn) => { timers.push(fn); return timers.length; }, clearInterval: () => {}, setTimeout,
    navigator: { locks },
    window: { electronAPI: bridge, location: {}, addEventListener: (name, handler) => {
      if (!handlers.has(name)) handlers.set(name, new Set());
      handlers.get(name).add(handler);
    } }, URLSearchParams,
  });
  function load(relative) {
    if (cache.has(relative)) return cache.get(relative);
    const exports = {};
    cache.set(relative, exports);
    context.exports = exports;
    context.require = (name) => {
      if (name === '../auth-session') return load('st-web/src/auth-session.ts');
      if (name === '../lib/api') return load('st-web/src/lib/api.ts');
      if (modules[name]) return modules[name];
      return require(name);
    };
    // 每个模块独立包裹，不能让顶层 const 在同一 VM 中互相重声明。
    const fn = vm.runInContext(`(function(exports, require) { ${compile(relative)}\n})`, context, { filename: relative });
    fn(exports, context.require);
    return exports;
  }
  const auth = load('st-web/src/auth-session.ts');
  const api = load('st-web/src/lib/api.ts').default;
  return {
    auth, api, localStorage, sessionStorage, timers, warnings, emitStorage,
    loadStore: () => load('st-web/src/store/auth.ts').useAuthStore,
    setBase: (value) => { currentBase = value; },
  };
}

module.exports = { loadDesktop, loadWeb, makeBridge, memoryStorage, sharedStorage, webLocks };
