import { app, BrowserWindow, shell } from 'electron';
import path from 'path';
import { initDatabase } from './database';
import { registerIpcHandlers } from './ipc-handlers';
import { resumePendingUploads } from './upload-manager';
import { resumePendingDownloads } from './download-manager';
import { resumeSyncEngines } from './sync-manager';
import { loadServerUrl } from './server-config';
import { getToken } from './api-client';
import { createMiniWindow, closeMiniWindow } from './mini-window';
import { prepareMenuWindow, closeMenuWindow } from './menu-window';

const isDev = !app.isPackaged;

async function createWindow(): Promise<void> {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    title: '星云盘',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // 外部链接用系统浏览器打开
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
      shell.openExternal(url);
      return { action: 'deny' };
    }
    return { action: 'allow' };
  });

  // F5 = 仅刷新文件列表：拦截窗口级刷新，通知渲染进程原地重新拉取当前目录（不整页重载、不丢状态）
  win.webContents.on('before-input-event', (event, input) => {
    if (input.type === 'keyDown' && input.key === 'F5') {
      event.preventDefault();
      win.webContents.send('app:refresh-file-list');
    }
  });

  if (isDev) {
    // 开发模式：加载 Vite dev server
    await win.loadURL('http://localhost:5173');
    win.webContents.openDevTools();
  } else {
    // 生产模式：加载打包的前端文件
    await win.loadFile(path.join(process.resourcesPath, 'web', 'index.html'));
  }

  // 主窗口关闭时销毁桌面悬浮窗，保持"关窗即退出"
  win.on('closed', () => {
    closeMiniWindow();
    closeMenuWindow();
  });
}

/**
 * 等待前端同步 auth token（最多等待 timeoutMs 毫秒）
 * 前端加载后调用 window.electronAPI.setAuth(token, refreshToken) 设置 token
 */
function waitForAuth(timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    const start = Date.now();
    const check = () => {
      if (getToken()) {
        resolve();
        return;
      }
      if (Date.now() - start >= timeoutMs) {
        console.warn('[main] auth token not received within timeout, sync resume may fail');
        resolve();
        return;
      }
      setTimeout(check, 200);
    };
    check();
  });
}

app.whenReady().then(async () => {
  // 加载服务器地址配置
  loadServerUrl();

  // 初始化数据库
  await initDatabase();

  // 注册 IPC 处理器
  registerIpcHandlers();

  // 恢复未完成的传输任务（标记为 paused，等用户手动恢复）
  await resumePendingUploads();
  await resumePendingDownloads();

  // 创建窗口
  await createWindow();
  // 桌面传输悬浮小窗（独立、置顶、可拖动）
  createMiniWindow();
  // 预加载右键菜单小窗（隐藏），保证首次右键打开不卡顿
  prepareMenuWindow();

  // 恢复同步引擎：等待前端加载并同步 auth token 后再执行
  // 前端在 App 初始化时调用 window.electronAPI.setAuth(token, refreshToken)
  // 这里最多等待 10 秒，token 到达后立即恢复同步
  await waitForAuth(10000);
  await resumeSyncEngines();

  app.on('activate', async () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      await createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
