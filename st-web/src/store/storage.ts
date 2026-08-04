import { create } from 'zustand';
import api from '../lib/api';
import type { StorageInfo } from '../types';

interface StorageState {
  storage: StorageInfo | null;
  fetchStorage: () => Promise<void>;
}

export const useStorageStore = create<StorageState>((set) => ({
  storage: null,
  fetchStorage: async () => {
    try {
      const storage = await api.get<StorageInfo>('/file/storage');
      set({ storage });
    } catch {
      // 获取失败时保留上一次的存储信息
    }
  },
}));
