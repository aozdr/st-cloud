import axios, { AxiosInstance } from 'axios';
import { getServerUrl } from './server-config';

let API_BASE = (process.env.STCLOUD_API_URL || getServerUrl()) + '/api';

let token: string | null = null;
let refreshTokenValue: string | null = null;

let client: AxiosInstance = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
});

// 请求拦截：附加 JWT
client.interceptors.request.use((config) => {
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截：自动刷新 Token
client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry && refreshTokenValue) {
      originalRequest._retry = true;
      try {
        const res = await axios.post(`${API_BASE}/auth/refresh`, {
          refreshToken: refreshTokenValue,
        });
        const newToken = res.data?.data?.accessToken;
        if (newToken) {
          token = newToken;
          originalRequest.headers.Authorization = `Bearer ${newToken}`;
          return client(originalRequest);
        }
      } catch {
        // refresh 失败，放弃
      }
    }
    return Promise.reject(error);
  }
);

export function setAuth(newToken: string, newRefreshToken: string): void {
  token = newToken;
  refreshTokenValue = newRefreshToken;
}

/** 服务器地址变更后刷新 baseURL */
export function setBaseUrl(url: string): void {
  API_BASE = url + '/api';
  client.defaults.baseURL = API_BASE;
}

export function getToken(): string | null {
  return token;
}

/** 从 JWT 中解析当前用户 ID（用于同步配置按用户隔离） */
export function getUserId(): string | null {
  if (!token) return null;
  try {
    const payload = token.split('.')[1];
    const decoded = JSON.parse(Buffer.from(payload, 'base64').toString('utf-8'));
    return decoded.userId != null ? String(decoded.userId) : null;
  } catch {
    return null;
  }
}

export { client as apiClient };
