import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosRequestConfig } from 'axios';
import { isElectron, syncAuthToElectron } from './electron';
import { getApiBaseUrl, getServerUrlSync } from './server-config';

const instance: AxiosInstance = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
});

/** 401 刷新 token 用：不经业务拦截器，且走 fetch adapter 以兼容 app:// 代理 */
const refreshClient = axios.create();

/** 服务器地址变更后调用，刷新 axios baseURL */
export function updateApiBaseUrl(): void {
  instance.defaults.baseURL = getApiBaseUrl();
}

/** 拼接文件流下载/预览 URL（token 入 query 仅作 Authorization 头不可用时的兜底） */
export function buildStreamUrl(nodeId: string | number, opts?: { token?: string | null; inline?: boolean }): string {
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
    const token = sessionStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

// Response interceptor: unwrap Result.data, attach business code, handle 401
let isRefreshing = false;
let refreshSubscribers: Array<(token: string | null) => void> = [];

function subscribeTokenRefresh(cb: (token: string | null) => void) {
  refreshSubscribers.push(cb);
}

function onRefreshed(token: string | null) {
  refreshSubscribers.forEach((cb) => cb(token));
  refreshSubscribers = [];
}

instance.interceptors.response.use(
  (response) => {
    // Blob 响应（如多文件打包下载 zip）直接透传，不走业务码解包
    if (response.config.responseType === 'blob') {
      return response.data;
    }
    const result = response.data;
    if (result.code === 200) {
      return result.data;
    }
    if (import.meta.env.DEV) console.error('API Error:', result.message);
    const error = new ApiError(result.message || 'Request failed', result.code);
    return Promise.reject(error);
  },
  async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry) {
      // If a refresh is already in flight, queue this request until it resolves
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          subscribeTokenRefresh((token) => {
            if (!token) return reject(error);
            originalRequest._retry = true;
            originalRequest.headers.Authorization = `Bearer ${token}`;
            resolve(instance(originalRequest));
          });
        });
      }

      isRefreshing = true;
      originalRequest._retry = true;
      const refreshToken = localStorage.getItem('refreshToken');
      if (refreshToken) {
        try {
          const res = await refreshClient.post(getApiBaseUrl() + '/auth/refresh', { refreshToken });
          const { token, refreshToken: newRefreshToken } = res.data.data;
          sessionStorage.setItem('accessToken', token);
          localStorage.setItem('refreshToken', newRefreshToken);
          syncAuthToElectron();
          isRefreshing = false;
          onRefreshed(token);
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return instance(originalRequest);
        } catch {
          isRefreshing = false;
          onRefreshed(null);
          sessionStorage.removeItem('accessToken');
          localStorage.removeItem('refreshToken');
          window.location.href = '/login';
          return Promise.reject(error);
        }
      }
      isRefreshing = false;
      onRefreshed(null);
      window.location.href = '/login';
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
