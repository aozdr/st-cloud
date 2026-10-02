import axios from 'axios';
import { getApiBaseUrl } from './lib/server-config';
import type { DesktopAuthSnapshot } from './types';

const STORAGE_KEY = 'stcloud:auth';
const REFRESH_LOCK_PREFIX = `${STORAGE_KEY}:refresh:`;
const ROTATION_MARKER_PREFIX = `${STORAGE_KEY}:rotation:`;
const REJECTED_REVISION_PREFIX = `${STORAGE_KEY}:rejected:`;
const REFRESH_LEASE_MS = 45_000; // 大于 HTTP 的 30 秒超时；过期所有者的结果不得触发退出。
const tabId = newSessionId();
type Credentials = DesktopAuthSnapshot;
export interface AuthContext { generation: number; sessionId: string; serverUrl: string }
let generation = 0;
let refreshFlight: { generation: number; promise: Promise<Credentials> } | null = null;
let desktopFlight: { generation: number; promise: Promise<void> } | null = null;
let observedStorage: string | null | undefined;
const listeners = new Set<(auth: Credentials) => void>();

function serverUrl(): string { return getApiBaseUrl().replace(/\/api$/, ''); }
function newSessionId(): string { return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`; }
function emptyCredentials(): Credentials {
  return { token: null, refreshToken: null, sessionId: newSessionId(), serverUrl: serverUrl(), revision: 0 };
}

function rejectionKey(auth: Credentials): string {
  return `${REJECTED_REVISION_PREFIX}${auth.sessionId}:${auth.revision}`;
}

function isRejectedRevision(auth: Credentials): boolean {
  // 当前修订已被拒绝时，更早的一次性令牌也不能再用于恢复；较新修订不受旧拒绝影响。
  return readRotationMarkers(REJECTED_REVISION_PREFIX).some((entry) => entry.serverUrl === auth.serverUrl
    && entry.sessionId === auth.sessionId && entry.revision >= auth.revision);
}

function loadCredentials(): Credentials {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    // 同步基线必须绑定真正采用的这次读取，不能再读一次后与内存快照错开。
    observedStorage = raw;
    if (raw) {
      const saved = JSON.parse(raw);
      if (saved.serverUrl === serverUrl() && typeof saved.sessionId === 'string'
          && (saved.token === null || typeof saved.token === 'string') && typeof saved.refreshToken === 'string' && saved.refreshToken) {
        const restored: Credentials = { ...saved, revision: Number.isFinite(saved.revision) ? saved.revision : 0 };
        if (isRejectedRevision(restored)) {
          // 自动拒绝只失效对应修订，不删除可能已被其它页替换的共享 pair。
          sessionStorage.removeItem('accessToken');
          return emptyCredentials();
        }
        // 单个持久记录保存完整令牌对；恢复时不读取可能只写了一半的旧键。
        if (saved.token) sessionStorage.setItem('accessToken', saved.token);
        else sessionStorage.removeItem('accessToken');
        localStorage.setItem('refreshToken', saved.refreshToken);
        return restored;
      }
      localStorage.removeItem(STORAGE_KEY);
      sessionStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      return emptyCredentials();
    }
  } catch { console.warn('认证持久记录暂时无法读取'); }
  // 兼容旧版安装：旧 refresh 键首次恢复后会升级为成对的持久记录。
  return { ...emptyCredentials(), token: sessionStorage.getItem('accessToken'), refreshToken: localStorage.getItem('refreshToken') };
}

let credentials = loadCredentials();

function syncBrowserAuth(): void {
  if (window.electronAPI) return; // 桌面仍以主进程 IPC 快照为准。
  let raw: string | null;
  let rejectedCurrent: boolean;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
    rejectedCurrent = !!credentials.refreshToken && isRejectedRevision(credentials);
  } catch { return; }
  if (raw === observedStorage && !rejectedCurrent) return;
  let latest: Credentials;
  if (raw === null) {
    // 只有见过共享成对记录后的删除才表示跨页退出，旧安装的 refresh 键仍可恢复。
    if (!observedStorage && !rejectedCurrent) { observedStorage = raw; return; }
    latest = emptyCredentials();
  } else {
    try {
      const saved = JSON.parse(raw);
      if (saved.serverUrl !== serverUrl() || typeof saved.sessionId !== 'string'
          || (saved.token !== null && typeof saved.token !== 'string')
          || typeof saved.refreshToken !== 'string' || !saved.refreshToken) return;
      latest = { ...saved, revision: Number.isFinite(saved.revision) ? saved.revision : 0 };
      // 旧存储修订的拒绝不能清除本页较新的有效内存对。
      if (latest.sessionId === credentials.sessionId && latest.revision < credentials.revision) {
        observedStorage = raw;
        if (!rejectedCurrent) return;
        latest = emptyCredentials();
      }
      if (isRejectedRevision(latest)) latest = emptyCredentials();
    } catch { return; }
  }
  observedStorage = raw;
  const changedSession = latest.sessionId !== credentials.sessionId;
  // storage 事件可能迟到；以当前存储为准，同一会话不得回滚内存中的较新修订。
  if (!changedSession && latest.revision <= credentials.revision) return;
  if (changedSession) {
    generation++;
    refreshFlight = null;
    desktopFlight = null;
    // 退出与新登录事件可能合并送达，先通知旧用户状态失效，避免新账号沿用旧资料。
    if (latest.refreshToken) {
      credentials = { ...latest, token: null, refreshToken: null };
      notify();
    }
  }
  credentials = latest;
  try {
    if (latest.token) sessionStorage.setItem('accessToken', latest.token);
    else sessionStorage.removeItem('accessToken');
  } catch { console.warn('认证令牌暂时无法持久化'); }
  notify();
}

function persist(): boolean {
  let pairSaved = false;
  try {
    if (credentials.refreshToken) {
      const raw = JSON.stringify(credentials);
      localStorage.setItem(STORAGE_KEY, raw);
      pairSaved = true;
      observedStorage = raw;
      if (credentials.token) sessionStorage.setItem('accessToken', credentials.token);
      else sessionStorage.removeItem('accessToken');
      localStorage.setItem('refreshToken', credentials.refreshToken);
    } else {
      localStorage.removeItem(STORAGE_KEY);
      pairSaved = true;
      observedStorage = null;
      sessionStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
    }
  } catch {
    // 轮换已经在服务端提交，落盘失败仍保留内存中的最新对，后续同步再次尝试。
    console.warn('认证令牌暂时无法持久化');
  }
  return pairSaved;
}

function notify(): void { listeners.forEach((listener) => listener({ ...credentials })); }

function ensureServer(): void {
  if (credentials.serverUrl === serverUrl()) { syncBrowserAuth(); return; }
  const previous = credentials.sessionId;
  generation++;
  refreshFlight = null;
  desktopFlight = null;
  credentials = emptyCredentials();
  persist();
  notify();
  void window.electronAPI?.clearAuth?.(previous).catch(() => {});
}

export function getCurrentAuth(): Credentials { ensureServer(); return { ...credentials }; }
export function captureAuthContext(): AuthContext {
  ensureServer();
  return { generation, sessionId: credentials.sessionId, serverUrl: credentials.serverUrl };
}
export function isCurrentAuth(context: AuthContext): boolean {
  ensureServer();
  return context.generation === generation && context.sessionId === credentials.sessionId && context.serverUrl === serverUrl();
}
export function subscribeAuth(listener: (auth: Credentials) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** 退出和新登录先使旧轮换失效，旧请求的失败也不能清除新会话。 */
export function beginAuthSession(): AuthContext {
  const previous = credentials.sessionId;
  generation++;
  refreshFlight = null;
  desktopFlight = null;
  credentials = emptyCredentials();
  persist();
  notify();
  void window.electronAPI?.clearAuth?.(previous).catch(() => {});
  return captureAuthContext();
}

function applyDesktopAuth(auth: Credentials): void {
  ensureServer();
  if (auth.sessionId !== credentials.sessionId || auth.serverUrl !== credentials.serverUrl
      || auth.revision < credentials.revision) return;
  credentials = { ...auth };
  if (!auth.refreshToken) {
    generation++;
    refreshFlight = null;
    desktopFlight = null;
  }
  persist();
  notify();
}

export async function syncDesktopAuth(): Promise<void> {
  ensureServer();
  const bridge = window.electronAPI;
  if (!bridge || !credentials.refreshToken) return;
  if (desktopFlight?.generation === generation) return desktopFlight.promise;
  const context = captureAuthContext();
  const pair = { ...credentials };
  const promise = (async () => {
    const latest = await bridge.setAuth(pair.token || '', pair.refreshToken!, pair.sessionId);
    if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
    // 主进程重启后修订号从 0 开始；仅当双方令牌对仍相同才重置基线。
    if (latest && latest.sessionId === credentials.sessionId && latest.serverUrl === credentials.serverUrl
        && latest.token === credentials.token && latest.refreshToken === credentials.refreshToken
        && latest.revision < credentials.revision) credentials = { ...credentials, revision: latest.revision };
    if (latest) applyDesktopAuth(latest);
    else persist(); // 兼容旧版桌面接口，后续仍由页面刷新。
  })().finally(() => {
    if (desktopFlight?.generation === context.generation) desktopFlight = null;
  });
  desktopFlight = { generation: context.generation, promise };
  return promise;
}

export async function saveLoginCredentials(pair: { token: string; refreshToken: string }, context: AuthContext): Promise<void> {
  if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
  if (!pair.token || !pair.refreshToken) throw new Error('登录响应缺少完整令牌对');
  credentials = { ...credentials, ...pair, revision: 0 };
  persist();
  notify();
  await syncDesktopAuth();
}

function isRejectionCode(code: unknown): boolean {
  // 后端业务异常可能是 HTTP 200：1002 用户失效，1004/1005 Token 过期/无效。
  return code === 401 || code === 403 || code === 1002 || code === 1004 || code === 1005;
}

function isAuthenticationRejection(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false;
  return error.response?.status === 401 || error.response?.status === 403
    || isRejectionCode(error.response?.data?.code);
}

interface RefreshTicket { owner: string; serverUrl: string; sessionId: string; revision: number; number: number; choosing: boolean; expiresAt: number }
interface RotationMarker { id: string; serverUrl: string; sessionId: string; revision: number }

function readCoordinationRecords(prefix: string): unknown[] {
  const keys = (): string[] => {
    const result: string[] = [];
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index);
      if (key?.startsWith(prefix)) result.push(key);
    }
    return result.sort();
  };
  // 按页记录避免单键“先读后写”丢失锁；复核键集合，避免其它页释放时索引移动漏读。
  for (let attempt = 0; attempt < 8; attempt++) {
    const before = keys();
    const records: unknown[] = [];
    for (const key of before) {
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      try {
        records.push(JSON.parse(raw));
      } catch { /* 非锁记录不参与排队 */ }
    }
    if (JSON.stringify(before) === JSON.stringify(keys())) return records;
  }
  throw new Error('标签页续期协调暂时繁忙');
}

function readRefreshTickets(): RefreshTicket[] {
  return readCoordinationRecords(REFRESH_LOCK_PREFIX).filter((value): value is RefreshTicket => {
    const ticket = value as Partial<RefreshTicket> | null;
    return typeof ticket?.owner === 'string' && typeof ticket.serverUrl === 'string'
      && Number.isFinite(ticket.number) && typeof ticket.choosing === 'boolean' && Number.isFinite(ticket.expiresAt);
  });
}

function readRotationMarkers(prefix = ROTATION_MARKER_PREFIX): RotationMarker[] {
  return readCoordinationRecords(prefix).filter((value): value is RotationMarker => {
    const marker = value as Partial<RotationMarker> | null;
    return typeof marker?.id === 'string' && typeof marker.serverUrl === 'string'
      && typeof marker.sessionId === 'string' && Number.isFinite(marker.revision);
  });
}

function removeSavedRotationMarkers(): void {
  try {
    for (const marker of readRotationMarkers()) {
      if (marker.serverUrl === credentials.serverUrl && marker.sessionId === credentials.sessionId
          && marker.revision < credentials.revision) localStorage.removeItem(ROTATION_MARKER_PREFIX + marker.id);
    }
  } catch { /* 删除失败留下的低修订标记不影响新对的续期与拒绝判断 */ }
}

function rejectBrowserRevision(context: AuthContext, submitted: Credentials): void {
  // 别页可在任意两次存储操作间完成新登录；写旧会话/修订墓碑，不对共享记录做非原子删除。
  if (!isCurrentAuth(context)) return;
  try {
    localStorage.setItem(rejectionKey(submitted), JSON.stringify({ id: `${submitted.sessionId}:${submitted.revision}`,
      serverUrl: submitted.serverUrl, sessionId: submitted.sessionId, revision: submitted.revision }));
    // 旧单键仅为兼容镜像；失效后移除，完整共享 pair 始终由对应修订标记保护。
    if (localStorage.getItem('refreshToken') === submitted.refreshToken) localStorage.removeItem('refreshToken');
  }
  catch { return; } // 无法可靠传播拒绝时保守保留，显式退出仍可处理。
  ensureServer();
}

async function withBrowserRefreshLock<T>(context: AuthContext, action: (canClear: () => boolean) => Promise<T>): Promise<T> {
  const locks = globalThis.navigator?.locks;
  if (locks) return locks.request(`${REFRESH_LOCK_PREFIX}${context.serverUrl}`, () => action(() => true));

  // 普通 HTTP 无 Web Locks 时使用 bakery 排队：先声明选号，再按号码和页 ID 依次进入。
  // 租约仅保存锁元数据；失效锁可能已经消耗服务端 refresh，因此接管后的拒绝只能保留会话。
  const key = REFRESH_LOCK_PREFIX + tabId;
  const ticket: RefreshTicket = { owner: tabId, serverUrl: context.serverUrl, sessionId: context.sessionId, revision: credentials.revision,
    number: 0, choosing: true, expiresAt: Date.now() + REFRESH_LEASE_MS };
  let uncertain = false;
  let acquired = false;
  try {
    try {
      localStorage.setItem(key, JSON.stringify(ticket));
      ticket.number = Math.max(0, ...readRefreshTickets().filter((entry) => entry.serverUrl === context.serverUrl).map((entry) => entry.number)) + 1;
      ticket.choosing = false;
      localStorage.setItem(key, JSON.stringify(ticket));
      while (true) {
        if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
        const now = Date.now();
        if (now >= ticket.expiresAt) throw new Error('等待其它标签页续期超时');
        const others = readRefreshTickets().filter((entry) => entry.owner !== tabId && entry.serverUrl === context.serverUrl);
        if (others.some((entry) => entry.expiresAt <= now && entry.sessionId === context.sessionId
            && entry.revision === credentials.revision)) uncertain = true;
        if (!others.some((entry) => entry.expiresAt > now && (entry.choosing || entry.number < ticket.number
            || (entry.number === ticket.number && entry.owner < tabId)))) break;
        await new Promise<void>((resolve) => setTimeout(resolve, 25));
      }
      acquired = true;
    } catch (error) {
      if (error instanceof Error && (error.message === '认证会话已变更' || error.message === '等待其它标签页续期超时')) throw error;
      // 存储不可写时仍允许单页恢复；无法证明排他性，永久拒绝也不得删除共享会话。
      uncertain = true;
    }
    return await action(() => {
      if (!acquired || uncertain || Date.now() >= ticket.expiresAt) return false;
      try { return localStorage.getItem(key) === JSON.stringify(ticket); } catch { return false; }
    });
  } finally {
    try {
      if (localStorage.getItem(key) === JSON.stringify(ticket)) localStorage.removeItem(key);
    } catch { /* 写失败留下的锁由租约过期释放 */ }
  }
}

async function refreshBrowserAuth(context: AuthContext, initial: Credentials): Promise<void> {
  const advanced = (pair: Credentials): boolean => credentials.sessionId === pair.sessionId
    && (credentials.revision > pair.revision || credentials.refreshToken !== pair.refreshToken);
  await withBrowserRefreshLock(context, async (canClear) => {
    ensureServer();
    if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
    // 排队期间其它页已轮换，直接复用完整最新对，不再消费旧 refresh。
    if (advanced(initial) && credentials.token && credentials.refreshToken) return;
    const submitted = { ...credentials };
    // 先记下可能被消费的修订；成功对未落盘时，别页不能把旧 refresh 拒绝误判为全局退出。
    // 每页/会话/修订一条，无令牌副本；连该标记也无法保存时不消费一次性 refresh。
    const marker: RotationMarker = { id: `${tabId}:${context.sessionId}:${submitted.revision}`,
      serverUrl: context.serverUrl, sessionId: context.sessionId, revision: submitted.revision };
    const markerKey = ROTATION_MARKER_PREFIX + marker.id;
    const markerRaw = JSON.stringify(marker);
    let hadPending: boolean;
    try {
      hadPending = localStorage.getItem(markerKey) !== null;
      localStorage.setItem(markerKey, markerRaw);
    } catch {
      throw new Error('认证持久化暂时不可用，请稍后重试');
    }
    let retainMarker = true;
    const mayClear = (): boolean => {
      if (hadPending || !canClear()) return false;
      try {
        return !readRotationMarkers().some((entry) => entry.id !== marker.id
          && entry.serverUrl === submitted.serverUrl && entry.sessionId === submitted.sessionId
          && entry.revision >= submitted.revision);
      } catch { return false; }
    };
    try {
      const response = await axios.post(getApiBaseUrl() + '/auth/refresh', { refreshToken: submitted.refreshToken }, { timeout: 30000 });
      ensureServer();
      if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
      if (advanced(submitted) && credentials.token && credentials.refreshToken) { retainMarker = false; return; }
      const pair = response.data?.data;
      if (response.data?.code !== 200 || typeof pair?.token !== 'string' || !pair.token
          || typeof pair.refreshToken !== 'string' || !pair.refreshToken) {
        if (isRejectionCode(response.data?.code)) {
          retainMarker = hadPending;
          if (mayClear()) rejectBrowserRevision(context, submitted);
        }
        throw new Error('刷新响应缺少完整令牌对');
      }
      credentials = { ...credentials, token: pair.token, refreshToken: pair.refreshToken, revision: submitted.revision + 1 };
      retainMarker = !persist();
      if (!retainMarker) removeSavedRotationMarkers();
      notify();
      // 旧版桥接仍接收最新对，避免兼容模式仅更新页面。
      await syncDesktopAuth();
    } catch (error) {
      ensureServer();
      if (isCurrentAuth(context)) {
        if (advanced(submitted) && credentials.token && credentials.refreshToken) { retainMarker = false; return; }
        if (isAuthenticationRejection(error)) {
          retainMarker = hadPending;
          if (mayClear()) rejectBrowserRevision(context, submitted);
        }
      }
      throw error;
    } finally {
      if (!retainMarker || !isCurrentAuth(context)) {
        try { if (localStorage.getItem(markerKey) === markerRaw) localStorage.removeItem(markerKey); }
        catch { /* 未能删除的标记保守保留，由成功的新修订清理 */ }
      }
    }
  });
}

export function refreshSession(): Promise<Credentials> {
  ensureServer();
  if (refreshFlight?.generation === generation) return refreshFlight.promise;
  if (!credentials.refreshToken) return Promise.reject(new Error('没有可用的刷新令牌'));
  const context = captureAuthContext();
  const initial = { ...credentials };
  const promise = (async () => {
    try {
      const bridge = window.electronAPI;
      if (bridge?.refreshAuth) {
        // 桌面主进程是唯一轮换者，业务 401、主动刷新和恢复都走同一 IPC。
        await syncDesktopAuth();
        const latest = await bridge.refreshAuth(context.sessionId);
        if (!isCurrentAuth(context)) throw new Error('认证会话已变更');
        applyDesktopAuth(latest);
      } else {
        await refreshBrowserAuth(context, initial);
      }
      if (!isCurrentAuth(context) || !credentials.token || !credentials.refreshToken) throw new Error('认证会话已变更');
      return { ...credentials };
    } catch (error) {
      if (isCurrentAuth(context)) {
        if (window.electronAPI?.getAuth) {
          try { applyDesktopAuth(await window.electronAPI.getAuth()); } catch { /* 暂时 IPC 错误不清除会话 */ }
        }
      }
      throw error;
    } finally {
      if (refreshFlight?.generation === context.generation) refreshFlight = null;
    }
  })();
  refreshFlight = { generation: context.generation, promise };
  return promise;
}

window.electronAPI?.onAuthChanged?.(applyDesktopAuth);
if (!window.electronAPI) window.addEventListener('storage', (event) => {
  if ((event.key === STORAGE_KEY || event.key === null || event.key?.startsWith(REJECTED_REVISION_PREFIX))
      && (!event.storageArea || event.storageArea === localStorage)) ensureServer();
});
