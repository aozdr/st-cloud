import { ipcMain, dialog, app, BrowserWindow, shell } from 'electron';
import { setAuth, setBaseUrl } from './api-client';
import { getServerUrl, saveServerUrl } from './server-config';
import { getAllTasks, deleteTask, getSyncHistory, getSyncStats } from './database';
import { getTransferSettings, setTransferSettings as applyTransferSettings, type TransferSettings } from './transfer-settings';
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
  stopAllSync, resumeSyncEngines, refreshExclusions, addExclusion as syncAddExclusion,
  removeExclusion as syncRemoveExclusion, setConflictStrategy as syncSetConflictStrategy,
  isWsConnected,
} from './sync-manager';
import { startMiniWindowDrag, stopMiniWindowDrag, resetMiniWindowPosition, openMainWindow, openTransferPage, openTransferSettings, showMiniWindow, hideMiniWindow } from './mini-window';
import { showMenuWindow, hideMenuWindow } from './menu-window';

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
  ipcMain.handle('transfer:getSettings', () => {
    return getTransferSettings();
  });

  ipcMain.handle('transfer:setSettings', (_event, settings: TransferSettings) => {
    applyTransferSettings(settings);
  });

  // ==================== 传输任务：全部暂停 / 全部开始 ====================
  ipcMain.handle('transfer:pauseAll', () => {
    const all = getAllTasks();
    for (const t of all) {
      if (t.status === 'uploading' || t.status === 'hashing' || t.status === 'merging' || t.status === 'pending') {
        pauseUpload(t.id);
      } else if (t.status === 'downloading') {
        pauseDownload(t.id);
      }
    }
  });

  ipcMain.handle('transfer:resumeAll', async () => {
    const all = getAllTasks();
    const resumes: Promise<void>[] = [];
    for (const t of all) {
      if (t.status === 'paused') {
        if (t.type === 'upload') resumes.push(resumeUpload(t.id));
        else resumes.push(resumeDownload(t.id));
      }
    }
    await Promise.all(resumes);
  });

  // ==================== 应用退出 ====================
  ipcMain.handle('app:quit', () => {
    app.quit();
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

  // ==================== 桌面传输悬浮小窗 ====================
  ipcMain.handle('mini:showMenu', () => {
    showMenuWindow();
  });
  ipcMain.handle('mini:hideMenu', () => {
    hideMenuWindow();
  });
  ipcMain.handle('mini:startDrag', (_event, handleX: number, handleY: number) => {
    startMiniWindowDrag(handleX, handleY);
  });
  ipcMain.handle('mini:endDrag', () => {
    stopMiniWindowDrag();
  });
  ipcMain.handle('mini:reset', () => {
    resetMiniWindowPosition();
  });
  ipcMain.handle('mini:openMain', () => {
    openMainWindow();
  });
  ipcMain.handle('mini:openTransfers', () => {
    openTransferPage();
  });
  ipcMain.handle('mini:openTransferSettings', () => {
    openTransferSettings();
  });
  ipcMain.handle('mini:show', () => {
    showMiniWindow();
  });
  ipcMain.handle('mini:hide', () => {
    hideMiniWindow();
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

  // Exclusions
  ipcMain.handle('sync:listExclusions', async (_event, rootId: string) => {
    return refreshExclusions(rootId);
  });
  ipcMain.handle('sync:addExclusion', async (_event, rootId: string, relativePath: string) => {
    return syncAddExclusion(rootId, relativePath);
  });
  ipcMain.handle('sync:removeExclusion', async (_event, rootId: string, exclusionId: string) => {
    return syncRemoveExclusion(rootId, exclusionId);
  });

  // Conflict strategy
  ipcMain.handle('sync:updateConflictStrategy', async (_event, rootId: string, strategy: string) => {
    return syncSetConflictStrategy(rootId, strategy);
  });

  // WS status
  ipcMain.handle('sync:wsStatus', () => {
    return isWsConnected();
  });

  // Sync history + stats
  ipcMain.handle('sync:getHistory', async (_event, rootId: string) => {
    return getSyncHistory(rootId);
  });
  ipcMain.handle('sync:getStats', async (_event, rootId: string) => {
    return getSyncStats(rootId);
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
