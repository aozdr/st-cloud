import { ipcMain, dialog, app, BrowserWindow, shell } from 'electron';
import { setAuth, setBaseUrl } from './api-client';
import { getServerUrl, saveServerUrl } from './server-config';
import { getAllTasks, deleteTask } from './database';
import { setTransferSettings as applyTransferSettings, type TransferSettings } from './transfer-settings';
import {
  startUpload,
  pauseUpload,
  resumeUpload,
  cancelUpload,
} from './upload-manager';
import {
  startDownload,
  pauseDownload,
  resumeDownload,
  cancelDownload,
} from './download-manager';
import {
  startSync, stopSync, registerSyncRoot, listSyncRoots, deleteSyncRoot, getSyncStatus,
  stopAllSync, resumeSyncEngines,
} from './sync-manager';

export function registerIpcHandlers(): void {
  // ==================== 认证 ====================
  ipcMain.handle('auth:set', async (_event, token: string, refreshToken: string) => {
    // 切换用户时先停止所有同步引擎，防止旧用户引擎用新 token 访问导致失败日志
    await stopAllSync();
    setAuth(token, refreshToken);
    // 按新用户身份恢复同步引擎
    resumeSyncEngines().catch((err) => {
      console.warn('[sync] resume after auth change failed:', String(err).substring(0, 100));
    });
  });

  // ==================== 服务器地址 ====================
  ipcMain.handle('server:getUrl', () => {
    return getServerUrl();
  });

  ipcMain.handle('server:setUrl', (_event, url: string) => {
    saveServerUrl(url);
    setBaseUrl(getServerUrl());
  });

  // ==================== 传输设置 ====================
  ipcMain.handle('transfer:setSettings', (_event, settings: TransferSettings) => {
    applyTransferSettings(settings);
  });

  // ==================== 上传 ====================
  ipcMain.handle('upload:start', (_event, filePath: string, parentId: string, replaceFileId?: string) => {
    return startUpload(filePath, parentId, replaceFileId);
  });

  ipcMain.handle('upload:pause', (_event, taskId: string) => {
    pauseUpload(taskId);
  });

  ipcMain.handle('upload:resume', async (_event, taskId: string) => {
    await resumeUpload(taskId);
  });

  ipcMain.handle('upload:cancel', async (_event, taskId: string) => {
    await cancelUpload(taskId);
  });

  // ==================== 下载 ====================
  ipcMain.handle('download:start', (_event, nodeId: string, fileName: string, fileSize: number, savePath: string) => {
    return startDownload(nodeId, fileName, fileSize, savePath);
  });

  ipcMain.handle('download:pause', (_event, taskId: string) => {
    pauseDownload(taskId);
  });

  ipcMain.handle('download:resume', async (_event, taskId: string) => {
    await resumeDownload(taskId);
  });

  ipcMain.handle('download:cancel', async (_event, taskId: string) => {
    await cancelDownload(taskId);
  });

  // ==================== 查询 ====================
  ipcMain.handle('tasks:getAll', () => {
    return getAllTasks();
  });

  // ==================== 删除任务记录（不中止传输） ====================
  ipcMain.handle('task:remove', (_event, taskId: string) => {
    deleteTask(taskId);
  });

  // ==================== 原生文件选择 ====================
  ipcMain.handle('dialog:selectFiles', async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openFile', 'multiSelections'],
    });
    return result.canceled ? [] : result.filePaths;
  });

  ipcMain.handle('dialog:selectFolder', async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openDirectory', 'multiSelections'],
    });
    return result.canceled ? [] : result.filePaths;
  });

  ipcMain.handle('dialog:selectSavePath', async (_event, fileName: string) => {
    const result = await dialog.showSaveDialog({
      defaultPath: fileName,
    });
    return result.canceled ? null : result.filePath;
  });

  ipcMain.handle('app:getDownloadsPath', () => {
    return app.getPath('downloads');
  });

  ipcMain.handle('shell:openPath', (_event, filePath: string) => {
    return shell.openPath(filePath);
  });

  ipcMain.handle('shell:showItemInFolder', (_event, filePath: string) => {
    shell.showItemInFolder(filePath);
  });

  ipcMain.handle('shell:trashItem', async (_event, filePath: string) => {
    await shell.trashItem(filePath);
  });

  // ==================== 文件同步 ====================
  ipcMain.handle('sync:register', async (_event, cloudFolderNodeId: string, localPath: string) => {
    return registerSyncRoot(cloudFolderNodeId, localPath);
  });

  ipcMain.handle('sync:listRoots', async () => {
    return listSyncRoots();
  });

  ipcMain.handle('sync:deleteRoot', async (_event, rootId: string) => {
    return deleteSyncRoot(rootId);
  });

  ipcMain.handle('sync:start', async (_event, rootId: string, cloudFolderNodeId: string, localPath: string) => {
    return startSync(rootId, cloudFolderNodeId, localPath);
  });

  ipcMain.handle('sync:stop', async (_event, rootId: string) => {
    return stopSync(rootId);
  });

  ipcMain.handle('sync:status', () => {
    return getSyncStatus();
  });
}

/**
 * 注册任务更新事件，向前端推送
 */
export function setupTaskUpdateForwarding(): void {
  // task:update 事件在 upload-manager / download-manager 中直接通过
  // BrowserWindow.getAllWindows().webContents.send('task:update', task) 发送
  // 前端通过 preload 中注册的 onTaskUpdate 回调接收
}
