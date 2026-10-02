import { create } from 'zustand';
import axios from 'axios';
import api from '../lib/api';
import { getApiBaseUrl } from '../lib/server-config';
import { beginAuthSession, captureAuthContext, getCurrentAuth, isCurrentAuth, refreshSession, saveLoginCredentials, subscribeAuth } from '../auth-session';
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
let restorePromise: Promise<void> | null = null;

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

/** 启动时只放行尚未到期的 access token，预留 30 秒时钟偏差。 */
function hasUsableAccessToken(token: string | null): boolean {
  if (!token) return false;
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof payload.exp === 'number' && payload.exp * 1000 > Date.now() + 30_000;
  } catch {
    return false;
  }
}

const initialAccessUsable = hasUsableAccessToken(getCurrentAuth().token);
const initialRefreshToken = getCurrentAuth().refreshToken;

/**
 * 主动刷新 access token：用 refreshToken 换取新的 token 对。
 * 与业务 401 和启动恢复共用同一轮换；暂时失败保留会话。
 */
async function proactivelyRefreshToken(): Promise<void> {
  if (!getCurrentAuth().refreshToken) return;
  try { await refreshSession(); } catch { /* 认证拒绝由会话事件统一清理，暂时错误下次重试 */ }
}

/**
 * 启动主动刷新定时器：定期检查 access token 是否到达生命周期 80%，
 * 到达则主动刷新，避免请求时恰好过期。
 */
function startRefreshTimer(): void {
  stopRefreshTimer();
  refreshTimer = setInterval(() => {
    const token = getCurrentAuth().token;
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
  authReady: boolean;
  loading: boolean;
  login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>;
  fetchUser: () => Promise<void>;
  restoreSession: () => Promise<void>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  // 仅有 refresh token 时先完成会话恢复，避免业务组件挂载后并发请求产生一批 401。
  isAuthenticated: initialAccessUsable || !!initialRefreshToken,
  authReady: initialAccessUsable || !initialRefreshToken,
  loading: false,

  login: async (req: LoginRequest) => {
    const context = beginAuthSession();
    const data: LoginResponse = await api.post('/auth/login', req);
    await saveLoginCredentials(data, context);
    if (!isCurrentAuth(context)) return;
    set({ isAuthenticated: true, authReady: true });
    startRefreshTimer();
    await useAuthStore.getState().fetchUser();
  },

  register: async (req: RegisterRequest) => {
    const context = beginAuthSession();
    const data: LoginResponse = await api.post('/auth/register', req);
    await saveLoginCredentials(data, context);
    if (!isCurrentAuth(context)) return;
    set({ isAuthenticated: true, authReady: true });
    startRefreshTimer();
    await useAuthStore.getState().fetchUser();
  },

  fetchUser: async () => {
    const context = captureAuthContext();
    set({ loading: true });
    try {
      const user: UserInfo = await api.get('/auth/me');
      if (!isCurrentAuth(context)) return;
      set({ user, loading: false });
      // 已认证但定时器未启动（如页面刷新后恢复会话）
      if (!refreshTimer) startRefreshTimer();
    } catch {
      if (isCurrentAuth(context)) set({ loading: false });
    }
  },

  restoreSession: () => {
    if (useAuthStore.getState().authReady) return Promise.resolve();
    if (restorePromise) return restorePromise;
    const context = captureAuthContext();
    const promise = (async () => {
      if (!getCurrentAuth().refreshToken) {
        set({ user: null, isAuthenticated: false, authReady: true });
        return;
      }
      try {
        await refreshSession();
        if (!isCurrentAuth(context)) return;
        set({ isAuthenticated: true, authReady: true });
        startRefreshTimer();
      } catch {
        if (!isCurrentAuth(context)) return;
        // 离线恢复结束路由等待，保留 refresh；网络恢复后业务请求可以再次刷新。
        set({ authReady: true, isAuthenticated: !!getCurrentAuth().refreshToken, loading: false });
      }
    })().finally(() => { if (restorePromise === promise) restorePromise = null; });
    restorePromise = promise;
    return promise;
  },

  logout: () => {
    const token = getCurrentAuth().token;
    const base = getApiBaseUrl();
    beginAuthSession();
    if (token) void axios.post(base + '/auth/logout', {}, { headers: { Authorization: `Bearer ${token}` }, timeout: 30000 }).catch(() => {});
    stopRefreshTimer();
    set({ user: null, isAuthenticated: false, authReady: true });
    useFavoritesStore.getState().reset();
  },
}));

// 主进程轮换/拒绝通过同一事件更新持久状态与路由，退出不会遗留旧用户资料。
subscribeAuth((auth) => {
  if (!auth.refreshToken) {
    stopRefreshTimer();
    restorePromise = null;
    useAuthStore.setState({ user: null, isAuthenticated: false, authReady: true, loading: false });
    useFavoritesStore.getState().reset();
  } else if (auth.token) {
    useAuthStore.setState({ isAuthenticated: true, authReady: true });
  }
});

// 有可用 access token 时直接启动定时器；仅有 refresh token 时由路由门禁先恢复会话。
if (initialAccessUsable) {
  startRefreshTimer();
}
