/**
 * 服务器地址配置
 * 前端通过 localStorage 持久化，Electron 模式下同步到主进程。
 * 浏览器（非 Electron）默认使用同源地址：由 nginx 托管时 /api 直接走反向代理。
 */

const STORAGE_KEY = 'stcloud:serverUrl';
const LEGACY_DEFAULT_URL = 'http://127.0.0.1:8080';
const LEGACY_DEFAULTS = new Set([LEGACY_DEFAULT_URL, 'http://localhost:8080']);

/**
 * 构建期环境默认地址：
 * - .env.development / .env.test / .env.production 里的 VITE_SERVER_URL
 * - 留空表示同源（开发走 Vite 代理，生产走 nginx 反代 /api）
 * - 允许误填 /api 后缀，自动去掉
 */
const ENV_SERVER_URL = normalizeEnv(import.meta.env.VITE_SERVER_URL);

function normalizeEnv(value: unknown): string {
  const text = typeof value === 'string' ? value.trim() : '';
  if (!text) return '';
  return normalize(text).replace(/\/api$/i, '');
}

function isElectronRuntime(): boolean {
  return typeof window !== 'undefined' && !!window.electronAPI;
}

/** 浏览器运行时的同源地址；Electron 或非 http(s) 环境返回 null。 */
function webOrigin(): string | null {
  if (typeof window === 'undefined' || isElectronRuntime()) {
    return null;
  }
  const origin = window.location?.origin;
  return origin && /^https?:/i.test(origin) ? origin : null;
}

function defaultServerUrl(): string {
  if (ENV_SERVER_URL) return ENV_SERVER_URL;
  return webOrigin() ?? LEGACY_DEFAULT_URL;
}

/** 规整地址：去掉末尾斜杠，补齐协议 */
export function normalize(url: string): string {
  let v = (url || '').trim();
  if (!v) return defaultServerUrl();
  if (!/^https?:\/\//i.test(v)) v = 'http://' + v;
  return v.replace(/\/+$/, '');
}

/**
 * 读取持久化地址。
 * 浏览器从 nginx 托管时，历史遗留的 127.0.0.1:8080 默认值不可达，自动迁移到同源，
 * 避免升级后首次登录出现 Network Error。
 */
function storedServerUrl(): string | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  const normalized = normalize(raw);
  const origin = webOrigin();
  // 只有未显式配置测试/正式地址时，才把历史 8080 默认迁移到同源；
  // 测试环境若显式指定了后端地址，不能被自动改写。
  if (!ENV_SERVER_URL && origin && LEGACY_DEFAULTS.has(normalized) && !LEGACY_DEFAULTS.has(origin)) {
    return origin;
  }
  return normalized;
}

/** 读取服务器地址（优先 Electron 主进程，其次 localStorage，最后默认值） */
export async function getServerUrl(): Promise<string> {
  const electron = window.electronAPI;
  if (electron?.getServerUrl) {
    try {
      const url = await electron.getServerUrl();
      if (url) return normalize(url);
    } catch {
      // 忽略，回退到 localStorage
    }
  }
  return storedServerUrl() || defaultServerUrl();
}

/** 同步读取服务器地址（不经过 Electron IPC，用于初始化 axios） */
export function getServerUrlSync(): string {
  return storedServerUrl() || defaultServerUrl();
}

/** 保存服务器地址并同步到 Electron 主进程 */
export async function setServerUrl(url: string): Promise<void> {
  const v = normalize(url);
  localStorage.setItem(STORAGE_KEY, v);
  const electron = window.electronAPI;
  if (electron?.setServerUrl) {
    try {
      await electron.setServerUrl(v);
    } catch {
      // 主进程持久化失败不影响前端使用
    }
  }
}

/** API 基址 = 服务器地址 + /api */
export function getApiBaseUrl(): string {
  return getServerUrlSync() + '/api';
}

/** 是否使用非默认地址 */
export function isCustomServer(): boolean {
  return getServerUrlSync() !== defaultServerUrl();
}
