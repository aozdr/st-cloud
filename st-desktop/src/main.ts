import { app, BrowserWindow, shell, protocol, net, Menu, Tray, nativeImage, dialog } from 'electron';
import path from 'path';
import fs from 'fs';
import { pathToFileURL } from 'url';
import { initDatabase } from './database';
import { getAllTasks } from './database';
import { registerIpcHandlers } from './ipc-handlers';
import { resumePendingUploads } from './upload-manager';
import { pauseUpload } from './upload-manager';
import { resumePendingDownloads } from './download-manager';
import { pauseDownload } from './download-manager';
import { resumeSyncEngines } from './sync-manager';
import { loadServerUrl } from './server-config';
import { getToken } from './api-client';
import { createMiniWindow, closeMiniWindow, showMiniWindow, openTransferPage, openTransferSettings } from './mini-window';
import { prepareMenuWindow, closeMenuWindow, hideMenuWindow } from './menu-window';

const isDev = !app.isPackaged;
let tray: Tray | null = null;

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
    // 隐藏系统标题栏，保留系统最小化/最大化/关闭按钮（titleBarOverlay）。
    // 页面内标题条右上角另有自定义圆形关闭按钮，置于系统按钮左侧避免遮挡。
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#ffffff',
      symbolColor: '#1f2430',
      height: 36,
    },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // 渲染层直连本机后端 API，服务端 CORS 无法预知桌面端来源，关闭同源校验；仅加载本地打包内容
      // TODO(P1#5 revisit): 移除 webSecurity:false 需先实测 app:// Origin 值并重启后端 CORS，本次回滚待重新设计
      webSecurity: false,
      sandbox: false,
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
  // 主窗口获得焦点（用户点击主界面）时关闭已弹出的右键菜单小窗
  win.on('focus', () => hideMenuWindow());
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

/** 托盘图标：打包后优先 resources/build/icon.png，开发态回退到仓库 build/icon.png */
function resolveTrayIcon(): Electron.NativeImage {
  const candidates = [
    path.join(process.resourcesPath, 'build', 'icon.png'),
    path.join(app.getAppPath(), 'build', 'icon.png'),
    path.join(__dirname, '..', 'build', 'icon.png'),
  ];
  for (const p of candidates) {
    try {
      if (fs.existsSync(p)) {
        const img = nativeImage.createFromPath(p);
        if (!img.isEmpty()) return img;
      }
    } catch {
      // 继续尝试下一个候选路径
    }
  }
  return nativeImage.createEmpty();
}

/** 暂停全部进行中传输（与 IPC transfer:pauseAll 相同逻辑，供托盘菜单调用） */
function pauseAllTransfersTray(): void {
  const all = getAllTasks();
  for (const t of all) {
    if (t.status === 'uploading' || t.status === 'hashing' || t.status === 'merging' || t.status === 'pending') {
      pauseUpload(t.id);
    } else if (t.status === 'downloading') {
      pauseDownload(t.id);
    }
  }
}

function createTray(): void {
  if (tray) return;
  const icon = resolveTrayIcon();
  if (icon.isEmpty()) {
    console.warn('[tray] 未找到托盘图标，跳过托盘创建');
    return;
  }
  tray = new Tray(icon);
  tray.setToolTip('星云盘');
  const menu = Menu.buildFromTemplate([
    {
      label: '显示传输悬浮窗',
      click: () => showMiniWindow(),
    },
    {
      label: '打开传输列表',
      click: () => openTransferPage(),
    },
    {
      label: '暂停全部',
      click: () => pauseAllTransfersTray(),
    },
    { type: 'separator' },
    {
      label: '传输设置',
      click: () => openTransferSettings(),
    },
    {
      label: '关于星云盘',
      click: () => {
        dialog.showMessageBox({
          type: 'info',
          title: '关于星云盘',
          message: '星云盘',
          detail: `版本 ${app.getVersion()}\n安全高效的企业云盘解决方案`,
          buttons: ['确定'],
        });
      },
    },
    { type: 'separator' },
    {
      label: '退出',
      click: () => app.quit(),
    },
  ]);
  tray.setContextMenu(menu);
  tray.on('double-click', () => showMiniWindow());
}

app.whenReady().then(async () => {
  // 去掉 Electron 默认菜单栏（File/Edit/View/Window/Help）
  Menu.setApplicationMenu(null);

  // 注册 app:// 协议：app://web/xxx → resources/web/xxx；SPA 路由无对应文件时回退 index.html
  const webRoot = path.resolve(process.resourcesPath, 'web');
  const safeIndex = pathToFileURL(path.join(webRoot, 'index.html')).toString();
  const fallbackIndex = () => net.fetch(safeIndex);
  protocol.handle('app', async (request) => {
    const url = new URL(request.url);
    // 防路径穿越：统一正斜杠并解码；仅允许解析结果落在 web 根内，否则回退 index.html
    let pathname = decodeURIComponent(url.pathname).replace(/\\/g, '/');
    if (pathname === '/' || pathname === '') pathname = '/index.html';
    const filePath = path.resolve(webRoot, '.' + pathname);
    if (!(filePath === webRoot || filePath.startsWith(webRoot + path.sep))) {
      return fallbackIndex();
    }
    try {
      if (!fs.statSync(filePath).isFile()) throw new Error('not a file');
    } catch {
      return fallbackIndex();
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
  // 系统托盘：提供悬浮窗显示/暂停全部/设置/关于/退出
  createTray();
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
