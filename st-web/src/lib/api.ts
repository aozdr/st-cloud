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

instance.interceptors.response.use(
  (response) => {
    const result = response.data;
    if (result.code === 200) {
      return result.data;
    }
    console.error('API Error:', result.message);
    const error = new Error(result.message || 'Request failed');
    (error as any).code = result.code;
    return Promise.reject(error);
  },
  async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry && !isRefreshing) {
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
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return instance(originalRequest);
        } catch {
          isRefreshing = false;
          localStorage.removeItem('accessToken');
          localStorage.removeItem('refreshToken');
          window.location.href = '/login';
          return Promise.reject(error);
        }
      }
      isRefreshing = false;
      window.location.href = '/login';
    }
    const msg = error.response?.data?.message || error.message || 'Network error';
    console.error('Request error:', msg);
    return Promise.reject(new Error(msg));
  },
);

// The response interceptor unwraps Result.data at runtime, so every method
// actually resolves to the payload T. Narrow the type so callers can use
// `await api.get<Foo>(...)` as `Foo` instead of AxiosResponse<Foo>.
interface ApiClient {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  patch<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
}

const api = instance as unknown as ApiClient;
export default api;