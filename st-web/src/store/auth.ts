import { create } from 'zustand';
import api from '../lib/api';
import { syncAuthToElectron } from '../lib/electron';
import type { LoginRequest, RegisterRequest, LoginResponse, UserInfo } from '../types';

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
    await useAuthStore.getState().fetchUser();
  },

  register: async (req: RegisterRequest) => {
    const data: LoginResponse = await api.post('/auth/register', req);
    localStorage.setItem('accessToken', data.token);
    localStorage.setItem('refreshToken', data.refreshToken);
    syncAuthToElectron();
    set({ isAuthenticated: true });
    await useAuthStore.getState().fetchUser();
  },

  fetchUser: async () => {
    set({ loading: true });
    try {
      const user: UserInfo = await api.get('/auth/me');
      set({ user, loading: false });
    } catch {
      set({ loading: false });
    }
  },

  logout: () => {
    api.post('/auth/logout').catch(() => {});
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    set({ user: null, isAuthenticated: false });
  },
}));
