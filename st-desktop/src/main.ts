import { app, BrowserWindow, shell, protocol, net, Menu } from 'electron';
import path from 'path';
import fs from 'fs';
import { pathToFileURL } from 'url';
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

// 打包后前端用自定义 app:// 协议加载：file:// 下 Vite 的 ES module 会被 Chromium CORS
// 拦截导致黑屏；app:// 注册为 standard/secure 特权协议后 module 可正常加载。
protocol.registerSchemesAsPrivileged([
  {
    scheme: 'app',
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true },
  },
]);

/** 诊断日志：渲染进程报错/加载失败会写到这里，便于定位黑屏等问题 */
function appendLog(line: string): void {
  try {
    const f = path.join(app.getPath('userData'), 'desktop-log.txt');
    fs.appendFileSync(f, `${new Date().toISOString()} ${line}\n`);
  } catch {
    // 忽略日志写入失败
  }
}

async function createWindow(): Promise<void> {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    title: '星云盘',
    // 白底：页面加载完成前避免黑色闪屏
    backgroundColor: '#ffffff',
    // 隐藏系统标题栏，改用页面内蓝色标题条；保留系统最小化/最大化/关闭按钮（白色符号）
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#2e6be6',
      symbolColor: '#ffffff',
      height: 36,
    },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      // 渲染进程需直连后端 API（127.0.0.1:8080），服务端 CORS 白名单桌面端无法预知，
      // 绕过 CORS 校验；仅加载本地打包内容，风险可控。
      webSecurity: false,
    },
  });

  // 诊断：记录渲染进程错误与页面加载结果
  win.webContents.on('console-message', (_e, _level, message) => {
    appendLog(`[renderer] ${message}`);
  });
  win.webContents.on('did-fail-load', (_e, code, desc, url) => {
    appendLog(`[fail] code=${code} desc=${desc} url=${url}`);
  });
  win.webContents.on('did-finish-load', () => {
    appendLog('[load-ok] index.html');
  });
  win.webContents.on('render-process-gone', (_e, details) => {
    appendLog(`[gone] ${details.reason} ${details.exitCode}`);
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
    // 生产模式：通过 app:// 协议加载打包的前端（file:// 无法加载 ES module）
    await win.loadURL('app://web/index.html');
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
  // 去掉 Electron 默认菜单栏（File/Edit/View/Window/Help）
  Menu.setApplicationMenu(null);

  // 注册 app:// 协议：app://web/xxx → resources/web/xxx；SPA 路由无对应文件时回退 index.html
  protocol.handle('app', (request) => {
    const url = new URL(request.url);
    let pathname = decodeURIComponent(url.pathname);
    if (pathname === '/' || pathname === '') pathname = '/index.html';
    const filePath = path.join(process.resourcesPath, 'web', pathname);
    try {
      if (!fs.statSync(filePath).isFile()) throw new Error('not a file');
    } catch {
      return net.fetch(pathToFileURL(path.join(process.resourcesPath, 'web', 'index.html')).toString());
    }
    return net.fetch(pathToFileURL(filePath).toString());
  });

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
