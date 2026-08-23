import { contextBridge, ipcRenderer } from 'electron';
import type { ElectronAPI, TransferTask, TransferSettings, WidgetMode } from './types';

/** Electron preload 运行于渲染进程，这里只声明本文件用到的 window.location 字段，避免引入全局 DOM lib 破坏 Node fetch 类型 */
declare const window: {
  location: { protocol: string; origin: string; pathname: string };
};

const api: ElectronAPI = {
  isElectron: true,

  // 窗口标题栏主题（含 Windows 最小化/最大化/关闭按钮颜色）
  setTitleBarTheme: (isDark: boolean) => {
    ipcRenderer.send('theme:set-overlay', isDark);
  },

  // 服务器地址
  getServerUrl: () => {
    return ipcRenderer.invoke('server:getUrl');
  },
  setServerUrl: (url: string) => {
    return ipcRenderer.invoke('server:setUrl', url);
  },

  // 认证
  setAuth: (token: string, refreshToken: string) => {
    ipcRenderer.invoke('auth:set', token, refreshToken);
  },

  // 传输设置
  getTransferSettings: () => {
    return ipcRenderer.invoke('transfer:getSettings');
  },
  setTransferSettings: (settings: TransferSettings) => {
    ipcRenderer.invoke('transfer:setSettings', settings);
  },
  onTransferSettingsChanged: (cb: (settings: TransferSettings) => void) => {
    const handler = (_event: unknown, settings: TransferSettings) => cb(settings);
    ipcRenderer.on('transfer:settings-changed', handler);
    return () => {
      ipcRenderer.removeListener('transfer:settings-changed', handler);
    };
  },
  pauseAllTransfers: () => {
    return ipcRenderer.invoke('transfer:pauseAll');
  },
  resumeAllTransfers: () => {
    return ipcRenderer.invoke('transfer:resumeAll');
  },

  // 退出整个应用
  quitApp: () => {
    return ipcRenderer.invoke('app:quit');
  },

  // 上传
  startUpload: (filePath: string, parentId: string, replaceFileId?: string) => {
    return ipcRenderer.invoke('upload:start', filePath, parentId, replaceFileId);
  },
  pauseUpload: (taskId: string) => {
    return ipcRenderer.invoke('upload:pause', taskId);
  },
  resumeUpload: (taskId: string) => {
    return ipcRenderer.invoke('upload:resume', taskId);
  },
  cancelUpload: (taskId: string) => {
    return ipcRenderer.invoke('upload:cancel', taskId);
  },

  // 下载
  startDownload: (nodeId: string, fileName: string, fileSize: number, savePath: string) => {
    return ipcRenderer.invoke('download:start', nodeId, fileName, fileSize, savePath);
  },
  pauseDownload: (taskId: string) => {
    return ipcRenderer.invoke('download:pause', taskId);
  },
  resumeDownload: (taskId: string) => {
    return ipcRenderer.invoke('download:resume', taskId);
  },
  cancelDownload: (taskId: string) => {
    return ipcRenderer.invoke('download:cancel', taskId);
  },

  // 查询
  getTasks: () => {
    return ipcRenderer.invoke('tasks:getAll');
  },

  // 事件监听
  onTaskUpdate: (cb: (task: TransferTask) => void) => {
    const handler = (_event: unknown, task: TransferTask) => cb(task);
    ipcRenderer.on('task:update', handler);
    // 返回取消监听函数
    return () => {
      ipcRenderer.removeListener('task:update', handler);
    };
  },

  // 原生文件选择
  selectFiles: () => {
    return ipcRenderer.invoke('dialog:selectFiles');
  },
  selectSavePath: (fileName: string) => {
    return ipcRenderer.invoke('dialog:selectSavePath', fileName);
  },
  selectFolder: () => {
    return ipcRenderer.invoke('dialog:selectFolder');
  },
  getDownloadsPath: () => {
    return ipcRenderer.invoke('app:getDownloadsPath');
  },
  openPath: (filePath: string) => {
    return ipcRenderer.invoke('shell:openPath', filePath);
  },
  showItemInFolder: (filePath: string) => {
    return ipcRenderer.invoke('shell:showItemInFolder', filePath);
  },

  // 删除任务记录
  removeTask: (taskId: string) => {
    return ipcRenderer.invoke('task:remove', taskId);
  },

  // 移到回收站
  trashItem: (filePath: string) => {
    return ipcRenderer.invoke('shell:trashItem', filePath);
  },

  // 文件同步
  syncRegister: (cloudFolderNodeId: string, localPath: string) => {
    return ipcRenderer.invoke('sync:register', cloudFolderNodeId, localPath);
  },
  syncListRoots: () => {
    return ipcRenderer.invoke('sync:listRoots');
  },
  syncDeleteRoot: (rootId: string) => {
    return ipcRenderer.invoke('sync:deleteRoot', rootId);
  },
  syncStart: (rootId: string, cloudFolderNodeId: string, localPath: string) => {
    return ipcRenderer.invoke('sync:start', rootId, cloudFolderNodeId, localPath);
  },
  syncStop: (rootId: string) => {
    return ipcRenderer.invoke('sync:stop', rootId);
  },
  syncStatus: () => {
    return ipcRenderer.invoke('sync:status');
  },
  syncListExclusions: (rootId: string) => {
    return ipcRenderer.invoke('sync:listExclusions', rootId);
  },
  syncAddExclusion: (rootId: string, relativePath: string) => {
    return ipcRenderer.invoke('sync:addExclusion', rootId, relativePath);
  },
  syncRemoveExclusion: (rootId: string, exclusionId: string) => {
    return ipcRenderer.invoke('sync:removeExclusion', rootId, exclusionId);
  },
  syncUpdateConflictStrategy: (rootId: string, strategy: string) => {
    return ipcRenderer.invoke('sync:updateConflictStrategy', rootId, strategy);
  },
  syncWsStatus: () => {
    return ipcRenderer.invoke('sync:wsStatus');
  },
  syncGetHistory: (rootId: string) => {
    return ipcRenderer.invoke('sync:getHistory', rootId);
  },
  syncGetStats: (rootId: string) => {
    return ipcRenderer.invoke('sync:getStats', rootId);
  },
  onSyncEvent: (cb: (event: { event: string; data: unknown }) => void) => {
    const handler = (_event: unknown, payload: { event: string; data: unknown }) => cb(payload);
    ipcRenderer.on('sync:event', handler);
    return () => {
      ipcRenderer.removeListener('sync:event', handler);
    };
  },

  // 主进程拦截 F5 后通知渲染进程原地刷新文件列表
  onRefreshFileList: (cb: () => void) => {
    const handler = () => cb();
    ipcRenderer.on('app:refresh-file-list', handler);
    return () => {
      ipcRenderer.removeListener('app:refresh-file-list', handler);
    };
  },

  // 桌面传输悬浮小窗
  showMiniMenu: () => {
    return ipcRenderer.invoke('mini:showMenu');
  },
  hideMiniMenu: () => {
    return ipcRenderer.invoke('mini:hideMenu');
  },
  /** 菜单小窗每次显示前，主进程通知页面重置回菜单列表视图 */
  onMenuReset: (cb: () => void) => {
    const handler = () => cb();
    ipcRenderer.on('mini-menu:reset', handler);
    return () => {
      ipcRenderer.removeListener('mini-menu:reset', handler);
    };
  },
  startMiniWindowDrag: (handleX?: number, handleY?: number) => {
    return ipcRenderer.invoke('mini:startDrag', handleX, handleY);
  },
  endMiniWindowDrag: () => {
    return ipcRenderer.invoke('mini:endDrag');
  },
  resetMiniWindowPosition: () => {
    return ipcRenderer.invoke('mini:reset');
  },
  setWidgetMode: (mode: WidgetMode) => {
    return ipcRenderer.invoke('mini:setWidgetMode', mode);
  },
  getWidgetMode: () => {
    return ipcRenderer.invoke('mini:getWidgetMode');
  },
  onWidgetThemeChanged: (cb: (isDark: boolean) => void) => {
    const handler = (_event: unknown, isDark: boolean) => cb(isDark);
    ipcRenderer.on('mini:theme-changed', handler);
    return () => {
      ipcRenderer.removeListener('mini:theme-changed', handler);
    };
  },
  openMainWindow: () => {
    return ipcRenderer.invoke('mini:openMain');
  },
  openTransfers: () => {
    return ipcRenderer.invoke('mini:openTransfers');
  },
  onOpenTransfers: (cb: () => void) => {
    const handler = () => cb();
    ipcRenderer.on('app:open-transfers', handler);
    return () => {
      ipcRenderer.removeListener('app:open-transfers', handler);
    };
  },
  openTransferSettings: () => {
    return ipcRenderer.invoke('mini:openTransferSettings');
  },
  onOpenTransferSettings: (cb: () => void) => {
    const handler = () => cb();
    ipcRenderer.on('app:open-transfer-settings', handler);
    return () => {
      ipcRenderer.removeListener('app:open-transfer-settings', handler);
    };
  },
  showMiniWindow: () => {
    return ipcRenderer.invoke('mini:show');
  },
  hideMiniWindow: () => {
    return ipcRenderer.invoke('mini:hide');
  },
};

/**
 * IPC 来源守卫：仅允许本应用可信页面（app:// / 开发 Vite / 打包 file:// 页面）
 * 调用 window.electronAPI 暴露的任何方法，防止不可信页面借 API 触发主进程文件操作。
 */
function isTrustedPage(): boolean {
  const proto = window.location.protocol;
  if (proto === 'app:') return true;
  if (proto === 'http:' || proto === 'https:') {
    const origin = window.location.origin;
    return origin === 'http://localhost:5173' || origin === 'http://127.0.0.1:5173';
  }
  if (proto === 'file:') {
    return window.location.pathname.includes('/web/') || window.location.pathname.includes('mini-transfer');
  }
  return false;
}

const guardedApi: ElectronAPI = { ...api };
for (const key of Object.keys(api) as Array<keyof ElectronAPI>) {
  const value = api[key];
  if (typeof value === 'function') {
    const original = value as (...args: unknown[]) => unknown;
    (guardedApi as unknown as Record<string, unknown>)[key] = (...args: unknown[]) => {
      if (!isTrustedPage()) return Promise.reject(new Error('Unauthorized'));
      return original.apply(null, args);
    };
  }
}

contextBridge.exposeInMainWorld('electronAPI', guardedApi);
