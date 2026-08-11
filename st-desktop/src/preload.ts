import { contextBridge, ipcRenderer } from 'electron';
import type { ElectronAPI, TransferTask, TransferSettings } from './types';

const api: ElectronAPI = {
  isElectron: true,

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
  setTransferSettings: (settings: TransferSettings) => {
    ipcRenderer.invoke('transfer:setSettings', settings);
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
};

contextBridge.exposeInMainWorld('electronAPI', api);
