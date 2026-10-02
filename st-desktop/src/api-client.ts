import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { getServerUrl } from './server-config';
import { AsyncLocalStorage } from 'node:async_hooks';

let API_BASE = (process.env.STCLOUD_API_URL || getServerUrl()) + '/api';

let token: string | null = null;
let refreshTokenValue: string | null = null;
let sessionId = '';
let generation = 0;
const authScope = new AsyncLocalStorage<number>();

/** 异步恢复的整条调用链绑定原会话，正常令牌续期不改变代次。 */
export function captureAuthGeneration(): number { return authScope.getStore() ?? generation; }
export function assertAuthGeneration(expected: number): void {
  if (expected !== generation) throw new Error('认证会话已变更');
}
export function runWithAuthGeneration<T>(expected: number, action: () => T): T {
  assertAuthGeneration(expected);
  return authScope.run(expected, action);
}
let revision = 0;
let refreshFlight: { generation: number; promise: Promise<AuthSnapshot> } | null = null;
const authListeners = new Set<(auth: AuthSnapshot) => void>();

export interface AuthSnapshot {
  token: string | null;
  refreshToken: string | null;
  sessionId: string;
  serverUrl: string;
  revision: number;
}

type AuthRequest = InternalAxiosRequestConfig & {
  _retry?: boolean;
  _authGeneration?: number;
  _authToken?: string | null;
};

export function getAuth(): AuthSnapshot {
  return { token, refreshToken: refreshTokenValue, sessionId, serverUrl: API_BASE.slice(0, -4), revision };
}

export function onAuthChanged(listener: (auth: AuthSnapshot) => void): () => void {
  authListeners.add(listener);
  return () => { authListeners.delete(listener); };
}

function notifyAuth(): void {
  const auth = getAuth();
  authListeners.forEach((listener) => listener(auth));
}

/** 仅明确的认证拒绝清除会话；断网、超时和 5xx 仍保留可恢复令牌。 */
function isRejectionCode(code: unknown): boolean {
  // 与后端 ResultCode 一致：未认证/无权限、用户失效、Token 过期和无效。
  return code === 401 || code === 403 || code === 1002 || code === 1004 || code === 1005;
}

function isAuthenticationRejection(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false;
  const status = error.response?.status;
  const code = error.response?.data?.code;
  return status === 401 || status === 403 || isRejectionCode(code);
}

export function clearAuth(expectedSessionId?: string): AuthSnapshot {
  if (expectedSessionId !== undefined && expectedSessionId !== sessionId) return getAuth();
  generation++;
  refreshFlight = null;
  token = null;
  refreshTokenValue = null;
  revision++;
  notifyAuth();
  return getAuth();
}

/** 所有主进程请求和渲染器 IPC 共用一次轮换，旧会话的成功/失败均不得落入新会话。 */
export function refreshAuth(expectedSessionId?: string): Promise<AuthSnapshot> {
  if (expectedSessionId !== undefined && expectedSessionId !== sessionId) {
    return Promise.reject(new Error('认证会话已变更'));
  }
  if (refreshFlight?.generation === generation) return refreshFlight.promise;
  if (!refreshTokenValue) return Promise.reject(new Error('没有可用的刷新令牌'));
  const startedGeneration = generation;
  const startedBase = API_BASE;
  const startedRefresh = refreshTokenValue;
  const promise = (async () => {
    try {
      const response = await axios.post(`${startedBase}/auth/refresh`, { refreshToken: startedRefresh }, { timeout: 30000 });
      if (startedGeneration !== generation || startedBase !== API_BASE) throw new Error('认证会话已变更');
      const credentials = response.data?.data;
      if (response.data?.code !== 200 || typeof credentials?.token !== 'string' || !credentials.token
          || typeof credentials.refreshToken !== 'string' || !credentials.refreshToken) {
        const invalid = new Error('刷新响应缺少完整令牌对');
        if (isRejectionCode(response.data?.code)) clearAuth(sessionId);
        throw invalid;
      }
      token = credentials.token;
      refreshTokenValue = credentials.refreshToken;
      revision++;
      notifyAuth();
      return getAuth();
    } catch (error) {
      if (startedGeneration === generation && startedBase === API_BASE && isAuthenticationRejection(error)) {
        clearAuth(sessionId);
      }
      throw error;
    } finally {
      if (refreshFlight?.generation === startedGeneration) refreshFlight = null;
    }
  })();
  refreshFlight = { generation: startedGeneration, promise };
  return promise;
}

let client: AxiosInstance = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
});

// 请求拦截：附加 JWT
client.interceptors.request.use((config: AuthRequest) => {
  assertAuthGeneration(captureAuthGeneration());
  // 重放也必须属于原会话，禁止退出/换服后把旧操作发送到新账号。
  if (config._authGeneration !== undefined && config._authGeneration !== generation) {
    throw new Error('认证会话已变更');
  }
  config._authGeneration = generation;
  config._authToken = token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  } else {
    delete config.headers.Authorization;
  }
  return config;
});

// 响应拦截：自动刷新 Token
client.interceptors.response.use(
  (response) => {
    // 丢弃旧恢复的迟到成功响应，不能继续启动引擎或改写本地配置。
    assertAuthGeneration((response.config as AuthRequest)._authGeneration ?? generation);
    return response;
  },
  async (error) => {
    const originalRequest: AuthRequest | undefined = error.config;
    if (error.response?.status === 401 && originalRequest && !originalRequest._retry && refreshTokenValue
        && originalRequest._authGeneration === generation) {
      originalRequest._retry = true;
      try {
        // 较旧 access 的迟到 401 直接用最新 access，不能再次消耗一次性 refresh。
        if (!token || originalRequest._authToken === token) await refreshAuth(sessionId);
        if (originalRequest._authGeneration !== generation || !token) throw new Error('认证会话已变更');
        originalRequest.headers.Authorization = `Bearer ${token}`;
        return client(originalRequest);
      } catch {
        // refresh 失败，放弃
      }
    }
    return Promise.reject(error);
  }
);

export function setAuth(newToken: string, newRefreshToken: string, newSessionId?: string): AuthSnapshot {
  // 同一渲染会话只初始化一次；迟到的同步 IPC 不得覆写主进程已经轮换的令牌。
  if (newSessionId && newSessionId === sessionId) return getAuth();
  if (!newSessionId && newToken === token && newRefreshToken === refreshTokenValue) return getAuth();
  generation++;
  refreshFlight = null;
  revision = 0;
  sessionId = newSessionId || `legacy-${generation}`;
  token = newToken || null;
  refreshTokenValue = newRefreshToken;
  notifyAuth();
  return getAuth();
}

/** 服务器地址变更后刷新 baseURL */
export function setBaseUrl(url: string): void {
  const next = url.replace(/\/+$/, '') + '/api';
  if (next === API_BASE) return;
  API_BASE = next;
  client.defaults.baseURL = API_BASE;
  // 服务器属于认证上下文；换服必须清空旧令牌并使在途响应失效。
  clearAuth();
}

export function getToken(): string | null {
  return token;
}

/** 从 JWT 中解析当前用户 ID（用于同步配置按用户隔离） */
export function getUserId(): string | null {
  if (!token) return null;
  try {
    const payload = token.split('.')[1];
    // JWT payload 为 base64url（含 -/_），先转换为标准 base64 再解码
    const base64url = payload.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = Buffer.from(base64url, 'base64').toString('utf-8');
    // JSON.parse 会先把未加引号的 Long 变成 JS Number，转回字符串时精度已丢失。
    const userId = decoded.match(/(?:^|[{,])\s*"userId"\s*:\s*(?:"([0-9]+)"|([0-9]+))\s*(?=[,}])/);
    return userId?.[1] ?? userId?.[2] ?? null;
  } catch {
    return null;
  }
}

export { client as apiClient };
