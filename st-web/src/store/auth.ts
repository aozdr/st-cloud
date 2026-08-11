import { create } from 'zustand';
import api from '../lib/api';
import { syncAuthToElectron } from '../lib/electron';
import { useFavoritesStore } from './favorites';
import type { LoginRequest, RegisterRequest, LoginResponse, UserInfo } from '../types';

/**
 * Access token 有效期（毫秒），与后端 stcloud.jwt.expiration 保持一致。
 * 用于主动刷新定时器计算：在 token 生命周期 80% 时触发刷新。
 */
const ACCESS_TOKEN_TTL = 7 * 24 * 60 * 60 * 1000; // 7 天
/** 主动刷新触发点：token 剩余 20% 生命周期时刷新 */
const REFRESH_THRESHOLD = 0.8;
/** 定时器检查间隔（每 10 分钟检查一次） */
const CHECK_INTERVAL = 10 * 60 * 1000;

let refreshTimer: ReturnType<typeof setInterval> | null = null;

/**
 * 从 JWT payload 解析签发时间，用于计算 token 已用生命周期比例。
 * JWT 格式: header.payload.signature，payload 是 base64url 编码的 JSON。
 */
function getTokenIssuedAt(token: string): number | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.iat ? payload.iat * 1000 : null;
  } catch {
    return null;
  }
}

/**
 * 主动刷新 access token：用 refreshToken 换取新的 token 对。
 * 失败时清除登录状态并跳转登录页。
 */
async function proactivelyRefreshToken(): Promise<void> {
  const refreshToken = localStorage.getItem('refreshToken');
  if (!refreshToken) return;

  try {
    const res = await api.post<{ token: string; refreshToken: string }>('/auth/refresh', { refreshToken });
    localStorage.setItem('accessToken', res.token);
    localStorage.setItem('refreshToken', res.refreshToken);
    syncAuthToElectron();
  } catch {
    // 刷新失败：清除登录态并跳转登录页
    useAuthStore.getState().logout();
    window.location.href = '/login';
  }
}

/**
 * 启动主动刷新定时器：定期检查 access token 是否到达生命周期 80%，
 * 到达则主动刷新，避免请求时恰好过期。
 */
function startRefreshTimer(): void {
  stopRefreshTimer();
  refreshTimer = setInterval(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) return;

    const issuedAt = getTokenIssuedAt(token);
    if (!issuedAt) return;

    const elapsed = Date.now() - issuedAt;
    const ratio = elapsed / ACCESS_TOKEN_TTL;
    if (ratio >= REFRESH_THRESHOLD) {
      proactivelyRefreshToken();
    }
  }, CHECK_INTERVAL);
}

function stopRefreshTimer(): void {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

interface AuthState {
  user: UserInfo | null;
  isAuthenticated: boolean;
  loading: boolean;
  login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>;
  fetchUser: () => Promise<void>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: !!localStorage.getItem('accessToken'),
  loading: false,

  login: async (req: LoginRequest) => {
    const data: LoginResponse = await api.post('/auth/login', req);
    localStorage.setItem('accessToken', data.token);
    localStorage.setItem('refreshToken', data.refreshToken);
    syncAuthToElectron();
    set({ isAuthenticated: true });
    startRefreshTimer();
    await useAuthStore.getState().fetchUser();
  },

  register: async (req: RegisterRequest) => {
    const data: LoginResponse = await api.post('/auth/register', req);
    localStorage.setItem('accessToken', data.token);
    localStorage.setItem('refreshToken', data.refreshToken);
    syncAuthToElectron();
    set({ isAuthenticated: true });
    startRefreshTimer();
    await useAuthStore.getState().fetchUser();
  },

  fetchUser: async () => {
    set({ loading: true });
    try {
      const user: UserInfo = await api.get('/auth/me');
      set({ user, loading: false });
      // 已认证但定时器未启动（如页面刷新后恢复会话）
      if (!refreshTimer) startRefreshTimer();
    } catch {
      set({ loading: false });
    }
  },

  logout: () => {
    api.post('/auth/logout').catch(() => {});
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    stopRefreshTimer();
    set({ user: null, isAuthenticated: false });
    useFavoritesStore.getState().reset();
  },
}));

// 页面刷新后：如果已认证，恢复主动刷新定时器
if (localStorage.getItem('accessToken')) {
  startRefreshTimer();
}