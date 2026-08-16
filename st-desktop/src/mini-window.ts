import { app, BrowserWindow, screen } from 'electron';
import path from 'path';
import fs from 'fs';
import { isMenuWindow } from './menu-window';

/**
 * 桌面传输悬浮小窗（类似百度网盘）：独立于主窗口、始终置顶、可拖动，
 * 实时展示上传/下载进度。任务数据通过 getAllWindows 广播的 task:update 自动接收。
 */

const isDev = !app.isPackaged;
/** 悬浮块固定尺寸：永不变化（右键菜单是独立小窗，不再撑大本窗口） */
const DEFAULT_WIDTH = 170;
const DEFAULT_HEIGHT = 36;
let miniWindow: BrowserWindow | null = null;
let saveTimer: NodeJS.Timeout | null = null;
let restoringSize = false;
let dragging = false;

/** 主进程拖拽状态：按下时记录锚点偏移，轮询跟随鼠标（鼠标移出小窗也不断流） */
let dragTimer: NodeJS.Timeout | null = null;
let dragState: { grabX: number; grabY: number; width: number; height: number } | null = null;
let lastCursor: { x: number; y: number } | null = null;
let dragMoved = false;

function boundsFile(): string {
  return path.join(app.getPath('userData'), 'mini-window-bounds.json');
}

function loadBounds(): { x: number; y: number } | null {
  try {
    const raw = fs.readFileSync(boundsFile(), 'utf8');
    const p = JSON.parse(raw);
    if (typeof p?.x === 'number' && typeof p?.y === 'number') return p;
  } catch {
    // 无记录时使用默认位置
  }
  return null;
}

function saveBounds(): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    if (!miniWindow || miniWindow.isDestroyed()) return;
    try {
      const [x, y] = miniWindow.getPosition();
      fs.writeFileSync(boundsFile(), JSON.stringify({ x, y }));
    } catch {
      // 忽略持久化失败
    }
  }, 400);
}

/** 校验保存的位置是否落在某个显示器工作区内（防换屏/改分辨率后跑出屏幕） */
function clampToDisplay(bounds: { x: number; y: number }, width: number, height: number): { x: number; y: number } | null {
  for (const d of screen.getAllDisplays()) {
    const a = d.workArea;
    if (bounds.x >= a.x - 40 && bounds.x <= a.x + a.width - 40
        && bounds.y >= a.y && bounds.y <= a.y + a.height - 40) {
      return {
        x: Math.min(Math.max(bounds.x, a.x), a.x + Math.max(0, a.width - width)),
        y: Math.min(Math.max(bounds.y, a.y), a.y + Math.max(0, a.height - height)),
      };
    }
  }
  return null;
}

/** 默认落点：主屏右下角 */
function defaultBounds(): { x: number; y: number } {
  const a = screen.getPrimaryDisplay().workArea;
  return {
    x: a.x + a.width - DEFAULT_WIDTH - 24,
    y: a.y + a.height - DEFAULT_HEIGHT - 24,
  };
}

export function createMiniWindow(): void {
  if (miniWindow && !miniWindow.isDestroyed()) return;
  const saved = loadBounds();
  const bounds = (saved ? clampToDisplay(saved, DEFAULT_WIDTH, DEFAULT_HEIGHT) : null)
    ?? defaultBounds();

  miniWindow = new BrowserWindow({
    width: DEFAULT_WIDTH,
    height: DEFAULT_HEIGHT,
    x: bounds.x,
    y: bounds.y,
    frame: false,
    transparent: true,
    // Windows 透明窗口必须显式全透明背景，否则会渲染成黑色方块
    backgroundColor: '#00000000',
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    // 透明窗口开阴影在部分显卡上会变黑块，改为无阴影（页面自带阴影）
    hasShadow: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  miniWindow.setAlwaysOnTop(true, 'floating');
  // 固定尺寸保护：禁最大化/全屏，拦截系统手势调整。
  // 注意：不用 setMinimumSize/setMaximumSize——Windows 无边框窗口会叠加系统边框内衬，
  // 最小=最大会与内衬冲突导致内容被挤偏（顶部出现空白）。统一用内容尺寸兜底恢复。
  miniWindow.setMaximizable(false);
  miniWindow.setFullScreenable(false);
  miniWindow.on('will-resize', (event) => {
    event.preventDefault();
  });
  // 兜底：任何路径导致窗口尺寸变化，立即恢复 180×44（按窗口外层尺寸）。
  // 不用 useContentSize：实测它会与 setBounds 冲突，导致拖拽时窗口忽大忽小。
  miniWindow.on('resize', () => {
    // 拖拽期间跳过：拖拽用锁定尺寸，避免守卫与 setBounds 互相干扰导致窗口时大时小
    if (!miniWindow || miniWindow.isDestroyed() || restoringSize || dragging) return;
    const [w, h] = miniWindow.getSize();
    if (w !== DEFAULT_WIDTH || h !== DEFAULT_HEIGHT) {
      restoringSize = true;
      const [x, y] = miniWindow.getPosition();
      miniWindow.setBounds({ x, y, width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT }, false);
      setTimeout(() => { restoringSize = false; }, 60);
    }
  });

  // 开发模式：清缓存并重试加载（避免 Vite 未就绪/旧缓存导致小窗空白或旧页面）
  const loadPage = (): void => {
    if (!miniWindow || miniWindow.isDestroyed()) return;
    const url = isDev
      ? 'http://localhost:5173/mini-transfer.html'
      : path.join(process.resourcesPath, 'web', 'mini-transfer.html');
    const attempt = isDev
      ? miniWindow.loadURL(url)
      : miniWindow.loadFile(url);
    attempt.catch(() => {
      console.warn('[mini] 小窗页面加载失败，1s 后重试:', url);
      setTimeout(loadPage, 1000);
    });
  };
  if (isDev) {
    miniWindow.webContents.session.clearCache().catch(() => { /* ignore */ });
  }
  console.log('[mini] 创建桌面传输悬浮窗, url=', isDev ? 'http://localhost:5173/mini-transfer.html' : path.join(process.resourcesPath, 'web', 'mini-transfer.html'));
  miniWindow.webContents.on('did-finish-load', () => {
    console.log('[mini] 页面加载完成');
  });
  loadPage();

  miniWindow.on('moved', saveBounds);
  // 兜底：无论任何原因跑到所有显示器可见区之外（含拖拽过程），立即拉回主屏右下角
  const guardTimer = setInterval(() => {
    if (!miniWindow || miniWindow.isDestroyed()) {
      clearInterval(guardTimer);
      return;
    }
    const [wx, wy] = miniWindow.getPosition();
    const [ww, wh] = miniWindow.getSize();
    const visible = screen.getAllDisplays().some((d) => {
      const a = d.workArea;
      return wx < a.x + a.width && wx + ww > a.x && wy < a.y + a.height && wy + wh > a.y;
    });
    if (!visible) {
      const b = defaultBounds();
      miniWindow.setBounds({ x: b.x, y: b.y, width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT }, false);
      saveBounds();
    }
  }, 800);
  miniWindow.on('closed', () => {
    if (saveTimer) clearTimeout(saveTimer);
    clearInterval(guardTimer);
    miniWindow = null;
  });
}

export function showMiniWindow(): void {
  if (!miniWindow || miniWindow.isDestroyed()) {
    createMiniWindow();
  } else {
    miniWindow.show();
    miniWindow.focus();
  }
}

export function hideMiniWindow(): void {
  if (miniWindow && !miniWindow.isDestroyed()) {
    miniWindow.hide();
  }
}

/**
 * 开始拖拽：handleX/handleY 为鼠标按下点相对窗口左上角的偏移（抓点，如按下窗口中间则约 115,32）。
 * 主进程直接轮询系统光标位置移动窗口——光标在哪窗口就跟到哪，
 * 鼠标移出悬浮窗也不会断流；鼠标不动时跳过移动，按住不放不会有任何副作用。
 */
export function startMiniWindowDrag(handleX = 0, handleY = 0): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  if (dragTimer) return; // 已在拖拽中
  // 锁定按下瞬间的窗口尺寸：Windows 缩放下 getSize 可能抖动，
  // 拖拽全程使用这份固定尺寸，避免窗口拖动时"时大时小"
  const [w, h] = miniWindow.getSize();
  dragState = { grabX: handleX, grabY: handleY, width: w, height: h };
  lastCursor = screen.getCursorScreenPoint();
  dragMoved = false;
  dragging = true;

  dragTimer = setInterval(() => {
    if (!miniWindow || miniWindow.isDestroyed() || !dragState) {
      stopMiniWindowDrag();
      return;
    }
    const cur = screen.getCursorScreenPoint();
    // 光标没动就什么都不做：避免"按住不动"时反复移动窗口（缩放≠100% 时的放大副作用）
    if (lastCursor && cur.x === lastCursor.x && cur.y === lastCursor.y) return;
    lastCursor = cur;

    const [wx2, wy2] = miniWindow.getPosition();
    const w = dragState.width;
    const h = dragState.height;
    // 目标位置 = 光标 - 抓点偏移：窗口移动后，按下时抓住的那一点仍保持在鼠标正下方，
    // 不会跳回窗口左上角。
    let nx = cur.x - dragState.grabX;
    let ny = cur.y - dragState.grabY;
    if (Math.abs(nx - wx2) + Math.abs(ny - wy2) > 4) dragMoved = true;
    // 钳制到光标所在显示器工作区，保证窗口主体始终可见
    const area = screen.getDisplayNearestPoint(cur).workArea;
    nx = Math.min(Math.max(nx, area.x), area.x + Math.max(0, area.width - w));
    ny = Math.min(Math.max(ny, area.y), area.y + Math.max(0, area.height - h));
    // 关键：Windows 系统缩放≠100% 时，拖拽用 setPosition 会被系统放大窗口；
    // 必须用 setBounds 并显式传入当前窗口尺寸，保证移动过程中大小不变。
    miniWindow.setBounds({ x: Math.round(nx), y: Math.round(ny), width: w, height: h }, false);
  }, 16);
}

/** 结束拖拽：停止轮询并持久化位置 */
export function stopMiniWindowDrag(): void {
  if (dragTimer) {
    clearInterval(dragTimer);
    dragTimer = null;
  }
  dragState = null;
  lastCursor = null;
  dragMoved = false;
  dragging = false;
  saveBounds();
}

/** 复位：把悬浮窗拉回主屏右下角默认位置（右键菜单/悬浮窗按钮触发） */
export function resetMiniWindowPosition(): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  const b = defaultBounds();
  miniWindow.setBounds({ x: b.x, y: b.y, width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT }, false);
  saveBounds();
}

/** 供菜单小窗定位：返回悬浮块当前屏幕位置与尺寸 */
export function getMiniWindowBounds(): { x: number; y: number; width: number; height: number } | null {
  if (!miniWindow || miniWindow.isDestroyed()) return null;
  return miniWindow.getBounds();
}

/** 点击悬浮窗回到主界面 */
export function openMainWindow(): void {
  // 排除悬浮窗与菜单小窗，定位主窗口
  const target = BrowserWindow.getAllWindows().find((w) => !w.isDestroyed() && w !== miniWindow && !isMenuWindow(w));
  if (!target) return;
  if (target.isMinimized()) target.restore();
  target.show();
  target.focus();
}

/** 打开主窗口并跳转到传输管理页 */
export function openTransferPage(): void {
  const target = BrowserWindow.getAllWindows().find((w) => !w.isDestroyed() && w !== miniWindow && !isMenuWindow(w));
  if (!target) return;
  if (target.isMinimized()) target.restore();
  target.show();
  target.focus();
  target.webContents.send('app:open-transfers');
}

/** 主窗口关闭时一并销毁悬浮窗，保持"关窗即退出"行为 */
export function closeMiniWindow(): void {
  if (miniWindow && !miniWindow.isDestroyed()) {
    saveBounds();
    miniWindow.destroy();
  }
  miniWindow = null;
}
