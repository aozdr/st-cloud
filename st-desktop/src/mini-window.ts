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
const DEFAULT_WIDTH = 150;
const DEFAULT_HEIGHT = 36;
let miniWindow: BrowserWindow | null = null;
let saveTimer: NodeJS.Timeout | null = null;
let dragging = false;
let sizeFixTimer: NodeJS.Timeout | null = null;

/** 主进程拖拽状态：按下时记录锚点偏移，轮询跟随鼠标（鼠标移出小窗也不断流） */
let dragTimer: NodeJS.Timeout | null = null;
let dragState: { grabX: number; grabY: number } | null = null;
let lastCursor: { x: number; y: number } | null = null;
let dragMoved = false;
let dragTarget: { x: number; y: number } | null = null; // 拖拽最后一次计算的目标位置
let fixAnchor: { x: number; y: number } | null = null; // 尺寸修正时锁定的位置

/**
 * 把悬浮窗内容尺寸钉回 150×36。
 * Windows 透明无边框窗口在移动/释放后可能被系统改动尺寸（一次改一点），
 * 这里用防抖 + 反复校验：最后一次尺寸变化 80ms 后若仍不准，继续修正直到精确。
 */
function scheduleSizeFix(anchor?: { x: number; y: number }): void {
  if (!miniWindow || miniWindow.isDestroyed() || dragging) return;
  if (anchor) fixAnchor = anchor;
  if (sizeFixTimer) clearTimeout(sizeFixTimer);
  sizeFixTimer = setTimeout(() => {
    sizeFixTimer = null;
    if (!miniWindow || miniWindow.isDestroyed() || dragging) return;
    const [cw, ch] = miniWindow.getContentSize();
    const [px, py] = miniWindow.getPosition();
    const tx = fixAnchor ? fixAnchor.x : px;
    const ty = fixAnchor ? fixAnchor.y : py;
    if (cw !== DEFAULT_WIDTH || ch !== DEFAULT_HEIGHT || px !== tx || py !== ty) {
      // 位置与尺寸一起钉回：不读取可能已被系统挪动的实时位置，锁定释放时的目标位置
      miniWindow.setBounds({ x: tx, y: ty, width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT }, false);
      // 系统可能延迟应用/再次改动，继续校验直到收敛
      scheduleSizeFix(fixAnchor ?? undefined);
    } else {
      fixAnchor = null;
    }
  }, 80);
}

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
  // 兜底：任何路径导致窗口尺寸变化，防抖修正回 150×36（拖拽期间跳过）
  miniWindow.on('resize', () => {
    scheduleSizeFix();
  });
  scheduleSizeFix();

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
  // 拖拽始终使用固定尺寸常量，不继承任何漂移后的尺寸；
  // Windows 缩放下 getSize 可能抖动，不能拿它当拖拽基准
  dragState = { grabX: handleX, grabY: handleY };
  lastCursor = screen.getCursorScreenPoint();
  dragMoved = false;
  dragging = true;
  // 拖拽开始时先把尺寸钉回标准值
  scheduleSizeFix();

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
    const w = DEFAULT_WIDTH;
    const h = DEFAULT_HEIGHT;
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
    dragTarget = { x: Math.round(nx), y: Math.round(ny) };
    miniWindow.setBounds({ ...dragTarget, width: w, height: h }, false);
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
  // 释放后把位置和尺寸一起钉回拖拽目标位置，防止系统挪动导致往左上漂移
  if (dragTarget) {
    scheduleSizeFix(dragTarget);
  } else if (miniWindow && !miniWindow.isDestroyed()) {
    const [x, y] = miniWindow.getPosition();
    scheduleSizeFix({ x, y });
  }
  dragTarget = null;
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

/** 打开主窗口并弹出传输设置对话框（与"传输管理"同样的打开方式） */
export function openTransferSettings(): void {
  const target = BrowserWindow.getAllWindows().find((w) => !w.isDestroyed() && w !== miniWindow && !isMenuWindow(w));
  if (!target) return;
  if (target.isMinimized()) target.restore();
  target.show();
  target.focus();
  target.webContents.send('app:open-transfer-settings');
}

/** 主窗口关闭时一并销毁悬浮窗，保持"关窗即退出"行为 */
export function closeMiniWindow(): void {
  if (miniWindow && !miniWindow.isDestroyed()) {
    saveBounds();
    miniWindow.destroy();
  }
  miniWindow = null;
}
