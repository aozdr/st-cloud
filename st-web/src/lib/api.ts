import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosRequestConfig } from 'axios';
import { isElectron } from './electron';
import { getApiBaseUrl, getServerUrlSync } from './server-config';
import { captureAuthContext, getCurrentAuth, isCurrentAuth, refreshSession, type AuthContext } from '../auth-session';

const instance: AxiosInstance = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
});

/** 服务器地址变更后调用，刷新 axios baseURL */
export function updateApiBaseUrl(): void {
  instance.defaults.baseURL = getApiBaseUrl();
}

/** 拼接文件流下载/预览 URL（token 入 query 仅作 Authorization 头不可用时的兜底） */
export function buildStreamUrl(nodeId: string, opts?: { token?: string | null; inline?: boolean }): string {
  const params = new URLSearchParams();
  if (opts?.token) params.set('token', opts.token);
  if (opts?.inline) params.set('inline', '1');
  const qs = params.toString();
  // Electron 下页面为 app://，相对 /api 会解析到 app://web 导致 404，需用绝对后端地址
  const base = isElectron() ? getServerUrlSync() : '';
  return `${base}/api/file/${nodeId}/stream${qs ? `?${qs}` : ''}`;
}

// ApiError carries the business error code from the server
class ApiError extends Error {
  code?: number;
  constructor(message: string, code?: number) {
    super(message);
    this.code = code;
  }
}

// Request interceptor: inject JWT
instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const request = config as InternalAxiosRequestConfig & { _authContext?: AuthContext; _authToken?: string | null };
    if (request._authContext && !isCurrentAuth(request._authContext)) throw new Error('认证会话已变更');
    request._authContext = captureAuthContext();
    const token = getCurrentAuth().token;
    request._authToken = token;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    } else {
      delete config.headers.Authorization;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

instance.interceptors.response.use(
  (response) => {
    // Blob 响应（如多文件打包下载 zip）直接透传，不走业务码解包
    if (response.config.responseType === 'blob') {
      return response.data;
    }
    const result = response.data;
    // DELETE/204 等成功响应可能没有响应体；按成功处理，避免访问 result.code 抛出异常。
    if (result == null || result === '') {
      return result;
    }
    if (result.code === 200) {
      return result.data;
    }
    if (import.meta.env.DEV) console.error('API Error:', result.message);
    const error = new ApiError(result.message || 'Request failed', result.code);
    return Promise.reject(error);
  },
  async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && originalRequest && !originalRequest._retry
        && !/\/auth\/(?:login|register|refresh|logout)(?:[/?]|$)/.test(originalRequest.url || '')
        && originalRequest._authContext && isCurrentAuth(originalRequest._authContext) && getCurrentAuth().refreshToken) {
      originalRequest._retry = true;
      try {
        // 并发共用刷新，旧 access 的迟到 401 只重放一次，不重复消耗 refresh。
        if (!getCurrentAuth().token || originalRequest._authToken === getCurrentAuth().token) await refreshSession();
        if (!isCurrentAuth(originalRequest._authContext) || !getCurrentAuth().token) throw new Error('认证会话已变更');
        originalRequest.headers.Authorization = `Bearer ${getCurrentAuth().token}`;
        return instance(originalRequest);
      } catch {
        return Promise.reject(error);
      }
    }
    const msg = error.response?.data?.message || error.message || 'Network error';
    if (import.meta.env.DEV) console.error('Request error:', msg);
    return Promise.reject(new Error(msg));
  },
);

// The response interceptor unwraps Result.data at runtime, so every method
// actually resolves to the payload T. Narrow the type so callers can use
// `await api.get<Foo>(...)` as `Foo` instead of AxiosResponse<Foo>.
interface ApiClient {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>;
  patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
}

const api = instance as unknown as ApiClient;
export default api;
