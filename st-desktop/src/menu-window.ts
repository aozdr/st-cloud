import { app, BrowserWindow, screen } from 'electron';
import path from 'path';

/**
 * 悬浮窗右键菜单小窗：独立固定尺寸（210×190），在悬浮块旁边弹出，
 * 不影响悬浮块窗口尺寸（悬浮块永远是固定方块，拖拽移动即可）。
 */

const isDev = !app.isPackaged;
const MENU_WIDTH = 260;
// 高度按"简易速度限制配置"视图完整内容（约183px）+ 卡片内边距定，避免按钮被裁切
const MENU_HEIGHT = 196;

let menuWindow: BrowserWindow | null = null;
let menuReady = false;
let lastShownAt = 0;

/** 判断是否为菜单小窗（供 openMainWindow 排除辅助窗口） */
export function isMenuWindow(win: BrowserWindow): boolean {
  return win === menuWindow;
}

function menuUrl(): string {
  return isDev
    ? 'http://localhost:5173/mini-menu.html'
    : path.join(process.resourcesPath, 'web', 'mini-menu.html');
}

function createMenuWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: MENU_WIDTH,
    height: MENU_HEIGHT,
    // 内容区严格等于 210×190，避免系统边框内衬造成内容偏移/空白
    useContentSize: true,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    movable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  win.setAlwaysOnTop(true, 'floating');
  menuReady = false;

  const load = (): void => {
    const attempt = isDev ? win.loadURL(menuUrl()) : win.loadFile(menuUrl());
    attempt.catch(() => {
      console.warn('[mini-menu] 菜单页加载失败，1s 后重试:', menuUrl());
      setTimeout(load, 1000);
    });
  };
  if (isDev) win.webContents.session.clearCache().catch(() => { /* ignore */ });
  win.webContents.on('did-finish-load', () => {
    menuReady = true;
  });
  load();

  // 失焦自动隐藏：但显示后 300ms 内的瞬时失焦忽略，避免"显示→焦点竞争→秒关"闪烁
  win.on('blur', () => {
    if (!win.isDestroyed() && Date.now() - lastShownAt > 300) win.hide();
  });
  win.on('closed', () => {
    menuWindow = null;
    menuReady = false;
  });
  return win;
}

/** 预创建菜单小窗（隐藏加载）：首次右键打开时页面已就绪，不闪空白、打开更流畅 */
export function prepareMenuWindow(): void {
  if (!menuWindow || menuWindow.isDestroyed()) {
    menuWindow = createMenuWindow();
  }
}

/** 在悬浮块旁边弹出菜单小窗（自动选择不越界的方位） */
export function showMenuWindow(): void {
  if (!menuWindow || menuWindow.isDestroyed()) {
    menuWindow = createMenuWindow();
  }
  // 从鼠标位置弹出（右击处），默认偏右下 4px；越界自动翻转到鼠标另一侧
  const cursor = screen.getCursorScreenPoint();
  const area = screen.getDisplayNearestPoint(cursor).workArea;
  let mx = cursor.x + 4;
  let my = cursor.y + 4;
  if (mx + MENU_WIDTH > area.x + area.width) mx = cursor.x - MENU_WIDTH - 4;
  if (my + MENU_HEIGHT > area.y + area.height) my = cursor.y - MENU_HEIGHT - 4;
  mx = Math.min(Math.max(mx, area.x), area.x + Math.max(0, area.width - MENU_WIDTH));
  my = Math.min(Math.max(my, area.y), area.y + Math.max(0, area.height - MENU_HEIGHT));

  const shownMx = mx;
  const shownMy = my;
  const showNow = (): void => {
    if (!menuWindow || menuWindow.isDestroyed()) return;
    menuWindow.setPosition(Math.round(shownMx), Math.round(shownMy));
    lastShownAt = Date.now();
    // 每次弹出都重置回菜单列表视图（避免上次停在"简易限速"设置页）
    menuWindow.webContents.send('mini-menu:reset');
    // show() 本身会显示并聚焦窗口；不要再额外调 focus()，
    // 否则透明无边框窗口会被系统激活两次，出现"闪两下"。
    // 已可见（重复右键）时只重定位，不重新 show，避免闪烁。
    if (!menuWindow.isVisible()) {
      menuWindow.show();
    }
  };
  if (menuReady || !menuWindow.webContents.isLoading()) {
    showNow();
  } else {
    // 首次打开且页面还没加载完：等加载完成再显示，避免先弹空白窗
    menuWindow.webContents.once('did-finish-load', showNow);
  }
}

export function hideMenuWindow(): void {
  if (menuWindow && !menuWindow.isDestroyed()) menuWindow.hide();
}

/** 主窗口关闭时一并销毁菜单小窗 */
export function closeMenuWindow(): void {
  if (menuWindow && !menuWindow.isDestroyed()) {
    menuWindow.destroy();
  }
  menuWindow = null;
}
