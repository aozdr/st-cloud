/**
 * 服务器地址配置
 * 前端通过 localStorage 持久化，Electron 模式下同步到主进程。
 */

const STORAGE_KEY = 'stcloud:serverUrl';
const DEFAULT_URL = 'http://127.0.0.1:8080';

/** 规整地址：去掉末尾斜杠，补齐协议 */
export function normalize(url: string): string {
  let v = (url || '').trim();
  if (!v) return DEFAULT_URL;
  if (!/^https?:\/\//i.test(v)) v = 'http://' + v;
  return v.replace(/\/+$/, '');
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
  return normalize(localStorage.getItem(STORAGE_KEY) || DEFAULT_URL);
}

/** 同步读取服务器地址（不经过 Electron IPC，用于初始化 axios） */
export function getServerUrlSync(): string {
  return normalize(localStorage.getItem(STORAGE_KEY) || DEFAULT_URL);
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
  return getServerUrlSync() !== DEFAULT_URL;
}
