/**
 * 传输设置 Store -- 管理上传/下载限速与最大并行任务数
 * 支持服务端下发的限速上限(serverLimits),与用户本地设置取最严格值后生效
 */
import { create } from 'zustand';
import { isElectron } from '../lib/electron';
import api from '../lib/api';

export interface TransferSettings {
  /** 最大并行任务数 (1~10) */
  maxParallelTasks: number;
  /** 上传限速 KB/s,0=不限速 */
  uploadSpeedLimit: number;
  /** 下载限速 KB/s,0=不限速 */
  downloadSpeedLimit: number;
}

interface ServerLimits {
  uploadSpeedLimit: number;
  downloadSpeedLimit: number;
}

const STORAGE_KEY = 'transferSettings';
const DEFAULT_SETTINGS: TransferSettings = {
  maxParallelTasks: 3,
  uploadSpeedLimit: 0,
  downloadSpeedLimit: 0,
};

function loadSettings(): TransferSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
    }
  } catch {
    // ignore
  }
  return { ...DEFAULT_SETTINGS };
}

function syncToElectron(settings: TransferSettings): void {
  if (!isElectron()) return;
  window.electronAPI!.setTransferSettings(settings);
}

/** 服务端上限与用户设置取最严格(最小且>0的值) */
function computeEffective(settings: TransferSettings, server: ServerLimits): TransferSettings {
  const cap = (s: number, u: number) => (s > 0 ? (u > 0 ? Math.min(s, u) : s) : u);
  return {
    maxParallelTasks: settings.maxParallelTasks,
    uploadSpeedLimit: cap(server.uploadSpeedLimit, settings.uploadSpeedLimit),
    downloadSpeedLimit: cap(server.downloadSpeedLimit, settings.downloadSpeedLimit),
  };
}

const initialSettings = loadSettings();
const initialServer: ServerLimits = { uploadSpeedLimit: 0, downloadSpeedLimit: 0 };
const initialEffective = computeEffective(initialSettings, initialServer);
syncToElectron(initialEffective);

interface TransferStore {
  settings: TransferSettings;
  serverLimits: ServerLimits;
  effective: TransferSettings;
  setSettings: (partial: Partial<TransferSettings>) => void;
  setServerLimits: (limits: Partial<ServerLimits>) => void;
  fetchServerLimits: () => Promise<void>;
}

export const useTransferStore = create<TransferStore>((set, get) => ({
  settings: initialSettings,
  serverLimits: initialServer,
  effective: initialEffective,
  setSettings: (partial) => {
    const settings = { ...get().settings, ...partial };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    const effective = computeEffective(settings, get().serverLimits);
    syncToElectron(effective);
    set({ settings, effective });
  },
  setServerLimits: (limits) => {
    const serverLimits = { ...get().serverLimits, ...limits };
    const effective = computeEffective(get().settings, serverLimits);
    syncToElectron(effective);
    set({ serverLimits, effective });
  },
  fetchServerLimits: async () => {
    try {
      const data = await api.get<ServerLimits>('/transfer/speed-limit');
      get().setServerLimits({
        uploadSpeedLimit: data?.uploadSpeedLimit || 0,
        downloadSpeedLimit: data?.downloadSpeedLimit || 0,
      });
    } catch {
      // ignore
    }
  },
}));