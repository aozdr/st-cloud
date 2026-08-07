import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosRequestConfig } from 'axios';
import { syncAuthToElectron } from './electron';
import { getApiBaseUrl, getServerUrlSync } from './server-config';

const instance: AxiosInstance = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
});

/** 服务器地址变更后调用，刷新 axios baseURL */
export function updateApiBaseUrl(): void {
  instance.defaults.baseURL = getApiBaseUrl();
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
    const token = localStorage.getItem('accessToken');
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
          const res = await axios.post(getServerUrlSync() + '/api/auth/refresh', { refreshToken });
          const { token, refreshToken: newRefreshToken } = res.data.data;
          localStorage.setItem('accessToken', token);
          localStorage.setItem('refreshToken', newRefreshToken);
          syncAuthToElectron();
          isRefreshing = false;
          onRefreshed(token);
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return instance(originalRequest);
        } catch {
          isRefreshing = false;
          onRefreshed(null);
          localStorage.removeItem('accessToken');
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