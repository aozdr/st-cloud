import { app, BrowserWindow, screen } from 'electron';
import path from 'path';
import fs from 'fs';
import { isMenuWindow } from './menu-window';
import { getAppThemeDark } from './theme-state';
import type { WidgetMode } from './types';

/**
 * 桌面传输悬浮小窗（类似百度网盘）：独立于主窗口、始终置顶、可拖动，
 * 实时展示上传/下载进度。任务数据通过 getAllWindows 广播的 task:update 自动接收。
 *
 * 支持三种显示模式：
 *  - micro    微型模式：只有云朵 + 上下行速度 + 关闭
 *  - compact  紧凑模式：云朵 + 上下行速度(带单位) + 展开按钮 + 关闭
 *  - expanded 展开模式：完整任务列表（含筛选、进度、暂停/继续操作、打开传输列表）
 *
 * 窗口尺寸跟随当前模式变化；拖拽结束后自动吸附最近屏幕边缘；多显示器下始终钳制在
 * 光标所在显示器工作区内，避免窗口跑出屏幕。
 */

const isDev = !app.isPackaged;

/** 三种模式的窗口尺寸（内容区，无边框透明窗口实际内容即整窗） */
const WIDGET_SIZES: Record<WidgetMode, { width: number; height: number }> = {
  micro: { width: 176, height: 40 },
  expanded: { width: 352, height: 520 },
};
/** 窗口四周透明留白：已移除阴影后无需留白，进度色直接铺满整窗（避免四周出现白色） */
const WIDGET_PADDING = 0;
const DEFAULT_MODE: WidgetMode = 'micro';

let miniWindow: BrowserWindow | null = null;
let widgetMode: WidgetMode = DEFAULT_MODE;
let saveTimer: NodeJS.Timeout | null = null;
let dragging = false;
let sizeFixTimer: NodeJS.Timeout | null = null;

/** 主进程拖拽状态：按下时记录锚点偏移，轮询跟随鼠标（鼠标移出小窗也不断流） */
let dragTimer: NodeJS.Timeout | null = null;
let dragState: { grabX: number; grabY: number } | null = null;
let lastCursor: { x: number; y: number } | null = null;
let dragTarget: { x: number; y: number } | null = null; // 拖拽最后一次计算的目标位置
let fixAnchor: { x: number; y: number } | null = null; // 尺寸修正时锁定的位置

/** 边缘吸附 / 模式切换的平滑动画 */
let animTimer: NodeJS.Timeout | null = null;
let animating = false;

function widgetSize(): { width: number; height: number } {
  const s = WIDGET_SIZES[widgetMode];
  return {
    width: s.width + WIDGET_PADDING * 2,
    height: s.height + WIDGET_PADDING * 2,
  };
}

/**
 * 把悬浮窗内容尺寸钉回当前模式的尺寸。
 * Windows 透明无边框窗口在移动/释放后可能被系统改动尺寸（一次改一点），
 * 这里用防抖 + 反复校验：最后一次尺寸变化 80ms 后若仍不准，继续修正直到精确。
 */
function scheduleSizeFix(anchor?: { x: number; y: number }): void {
  if (!miniWindow || miniWindow.isDestroyed() || dragging || animating) return;
  if (anchor) fixAnchor = anchor;
  if (sizeFixTimer) clearTimeout(sizeFixTimer);
  sizeFixTimer = setTimeout(() => {
    sizeFixTimer = null;
    if (!miniWindow || miniWindow.isDestroyed() || dragging || animating) return;
    const size = widgetSize();
    const [cw, ch] = miniWindow.getContentSize();
    const [px, py] = miniWindow.getPosition();
    const tx = fixAnchor ? fixAnchor.x : px;
    const ty = fixAnchor ? fixAnchor.y : py;
    // 带 1px 容差：Windows 透明窗口在移动/释放后可能被系统轻微挪动 1px，
    // 如果逐像素纠正会与系统“打架”而形成肉眼可见的抖动/回弹；容差内视为已稳定。
    if (Math.abs(cw - size.width) > 1 || Math.abs(ch - size.height) > 1
        || Math.abs(px - tx) > 1 || Math.abs(py - ty) > 1) {
      // 位置与尺寸一起钉回：不读取可能已被系统挪动的实时位置，锁定释放时的目标位置
      miniWindow.setBounds({ x: tx, y: ty, width: size.width, height: size.height }, false);
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

interface SavedBounds {
  x: number;
  y: number;
  mode?: WidgetMode;
}

function loadBounds(): SavedBounds | null {
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
      fs.writeFileSync(boundsFile(), JSON.stringify({ x, y, mode: widgetMode }));
    } catch {
      // 忽略持久化失败
    }
  }, 400);
}

/** 校验保存的位置是否落在某个显示器工作区内（防换屏/改分辨率后跑出屏幕） */
function clampToDisplay(
  bounds: { x: number; y: number },
  width: number,
  height: number
): { x: number; y: number } | null {
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
  const size = widgetSize();
  const a = screen.getPrimaryDisplay().workArea;
  return {
    x: a.x + a.width - size.width - 24,
    y: a.y + a.height - size.height - 24,
  };
}

export function createMiniWindow(): void {
  if (miniWindow && !miniWindow.isDestroyed()) return;
  const saved = loadBounds();
  if (saved?.mode && WIDGET_SIZES[saved.mode]) widgetMode = saved.mode;
  const size = widgetSize();
  const bounds = (saved ? clampToDisplay(saved, size.width, size.height) : null)
    ?? defaultBounds();

  miniWindow = new BrowserWindow({
    width: size.width,
    height: size.height,
    x: bounds.x,
    y: bounds.y,
    frame: false,
    transparent: true,
    // Windows 透明窗口必须显式全透明背景，否则会渲染成黑色方块
    backgroundColor: '#00000000',
    // 禁止用户手动拉边（悬浮窗边缘不再出现可缩放手柄）。
    // 模式切换时由 setMiniWidgetMode 临时开启 resizable 再用 setBounds 调整尺寸。
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    // 不抢主窗口焦点：悬浮窗通过鼠标交互，不接收键盘焦点
    focusable: false,
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
  // 兜底：任何路径导致窗口尺寸变化，防抖修正回当前模式尺寸（拖拽/动画期间跳过）
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
    // 立即同步主应用当前主题（避免首次打开与主窗口不一致）
    miniWindow?.webContents.send('mini:theme-changed', getAppThemeDark());
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
      miniWindow.setBounds({ x: b.x, y: b.y, width: ww, height: wh }, false);
      saveBounds();
    }
  }, 800);
  miniWindow.on('closed', () => {
    if (saveTimer) clearTimeout(saveTimer);
    clearInterval(guardTimer);
    cancelAnim();
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

/** 取消进行中的吸附/模式动画 */
function cancelAnim(): void {
  if (animTimer) {
    clearInterval(animTimer);
    animTimer = null;
  }
  animating = false;
}

/**
 * 平滑移动/缩放窗口到目标 bounds（easeOutCubic，约 200ms）。
 * 用户拖拽时立即打断动画，避免抢占光标控制权。
 */
function animateBoundsTo(
  target: { x: number; y: number; width: number; height: number },
  animateSize = true,
): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  cancelAnim();
  const from = miniWindow.getBounds();
  if (from.x === target.x && from.y === target.y && from.width === target.width && from.height === target.height) {
    scheduleSizeFix(target);
    return;
  }
  animating = true;
  // 拖拽吸附/复位场景：尺寸应由当前模式决定，若拖动期间被系统改动，立即回正，
  // 只对位置做缓动，避免“放大后再缩小”的可见动画。
  if (!animateSize) {
    miniWindow.setBounds({ x: from.x, y: from.y, width: target.width, height: target.height }, false);
  }
  const start = Date.now();
  const DURATION = 200;
  animTimer = setInterval(() => {
    if (!miniWindow || miniWindow.isDestroyed()) {
      cancelAnim();
      return;
    }
    const t = Math.min(1, (Date.now() - start) / DURATION);
    const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
    const x = Math.round(from.x + (target.x - from.x) * eased);
    const y = Math.round(from.y + (target.y - from.y) * eased);
    const w = animateSize ? Math.round(from.width + (target.width - from.width) * eased) : target.width;
    const h = animateSize ? Math.round(from.height + (target.height - from.height) * eased) : target.height;
    miniWindow.setBounds({ x, y, width: w, height: h }, false);
    if (t >= 1) {
      cancelAnim();
      scheduleSizeFix(target);
      saveBounds();
    }
  }, 16);
}

/**
 * 开始拖拽：handleX/handleY 为鼠标按下点相对窗口左上角的偏移（抓点）。
 * 主进程直接轮询系统光标位置移动窗口——光标在哪窗口就跟到哪，
 * 鼠标移出悬浮窗也不会断流；鼠标不动时跳过移动，按住不放不会有任何副作用。
 */
export function startMiniWindowDrag(handleX = 0, handleY = 0): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  if (dragTimer) return; // 已在拖拽中
  // 打断进行中的吸附/模式动画，立即切换到手动控制
  cancelAnim();
  // 拖拽始终使用当前模式固定尺寸，不继承任何漂移后的尺寸；
  // Windows 缩放下 getSize 可能抖动，不能拿它当拖拽基准
  dragState = { grabX: handleX, grabY: handleY };
  lastCursor = screen.getCursorScreenPoint();
  dragging = true;
  // 拖拽基线 = 当前窗口左上角；用于位置未变时跳过、以及"真正拖过"的判定
  const [startX, startY] = miniWindow.getPosition();
  dragTarget = { x: startX, y: startY };
  // 拖拽开始时先把尺寸钉回标准值
  scheduleSizeFix();

  // 约 16ms 轮询光标（对齐主流显示器刷新率），并仅在目标位置变化时 setBounds，
  // 避免过快 setBounds 与系统移动/缩放产生冲突导致抖动与回弹。
  dragTimer = setInterval(() => {
    if (!miniWindow || miniWindow.isDestroyed() || !dragState) {
      stopMiniWindowDrag();
      return;
    }
    const cur = screen.getCursorScreenPoint();
    // 光标没动就什么都不做：避免"按住不动"时反复移动窗口（缩放≠100% 时的放大副作用）
    if (lastCursor && cur.x === lastCursor.x && cur.y === lastCursor.y) return;
    lastCursor = cur;

    const base = dragTarget;
    const size = widgetSize();
    const w = size.width;
    const h = size.height;
  let nx = cur.x - dragState.grabX;
  let ny = cur.y - dragState.grabY;
  // 钳制到光标所在显示器工作区，保证窗口主体始终可见
  const area = screen.getDisplayNearestPoint(cur).workArea;
    nx = Math.min(Math.max(nx, area.x), area.x + Math.max(0, area.width - w));
    ny = Math.min(Math.max(ny, area.y), area.y + Math.max(0, area.height - h));
    // 关键：Windows 系统缩放≠100% 时，拖拽用 setPosition 会被系统放大窗口；
    // 必须用 setBounds 并显式传入当前窗口尺寸，保证移动过程中大小不变。
    const target = { x: Math.round(nx), y: Math.round(ny) };
    if (base && target.x === base.x && target.y === base.y) return;
    dragTarget = target;
    miniWindow.setBounds({ ...target, width: w, height: h }, false);
  }, 16);
}

/** 结束拖拽：停止轮询、边缘吸附并持久化位置 */
export function stopMiniWindowDrag(): void {
  if (dragTimer) {
    clearInterval(dragTimer);
    dragTimer = null;
  }
  dragState = null;
  lastCursor = null;
  dragging = false;
  const finalTarget = dragTarget;
  dragTarget = null;
  if (!miniWindow || miniWindow.isDestroyed()) return;

  const size = widgetSize();
  const [x, y] = miniWindow.getPosition();
  const base = finalTarget ?? { x, y };
  // 已移除边缘吸附：释放后直接落到拖拽终点，并钳制在当前显示器工作区内防止跑出屏幕
  const clamped = clampToDisplay(base, size.width, size.height) ?? base;
  miniWindow.setBounds({ x: clamped.x, y: clamped.y, width: size.width, height: size.height }, false);
  scheduleSizeFix(clamped);
  saveBounds();
}

/** 复位：把悬浮窗拉回主屏右下角默认位置（右键菜单/悬浮窗按钮触发） */
export function resetMiniWindowPosition(): void {
  if (!miniWindow || miniWindow.isDestroyed()) return;
  const size = widgetSize();
  const b = defaultBounds();
  animateBoundsTo({ x: b.x, y: b.y, width: size.width, height: size.height }, false);
}

/** 供菜单小窗定位：返回悬浮块当前屏幕位置与尺寸 */
export function getMiniWindowBounds(): { x: number; y: number; width: number; height: number } | null {
  if (!miniWindow || miniWindow.isDestroyed()) return null;
  return miniWindow.getBounds();
}

/** 判断窗口是否为悬浮窗（供 IPC 主题广播等场景识别） */
export function isMiniWindow(win: BrowserWindow): boolean {
  return win === miniWindow;
}

/** 当前悬浮窗模式 */
export function getMiniWidgetMode(): WidgetMode {
  return widgetMode;
}

/**
 * 设置悬浮窗显示模式：调整窗口尺寸并在当前显示器内重新钳制。
 * 改变模式时以左上角为锚点扩展/收缩，确保窗口主体始终可见。
 */
export function setMiniWidgetMode(mode: WidgetMode): void {
  if (!miniWindow || miniWindow.isDestroyed() || mode === widgetMode || !WIDGET_SIZES[mode]) return;
  // 先取消可能正在进行的吸附/旧模式动画，再一次性落位到新尺寸
  cancelAnim();
  widgetMode = mode;
  const size = widgetSize();
  const [cx, cy] = miniWindow.getPosition();
  const area = screen.getDisplayNearestPoint({
    x: cx + Math.floor(size.width / 2),
    y: cy + Math.floor(size.height / 2),
  }).workArea;
  const nx = Math.min(Math.max(cx, area.x), area.x + Math.max(0, area.width - size.width));
  const ny = Math.min(Math.max(cy, area.y), area.y + Math.max(0, area.height - size.height));
  // 直接 setBounds 到目标尺寸：透明无边框窗口上用动画改尺寸不可靠（可能被系统缩放/中断），
  // 导致展开模式下任务列表被裁剪。改为即时、确定性的 resize + 尺寸修正兜底。
  miniWindow.setResizable(true);
  miniWindow.setBounds({ x: nx, y: ny, width: size.width, height: size.height }, false);
  miniWindow.setResizable(false);
  scheduleSizeFix(nx !== undefined ? { x: nx, y: ny } : undefined);
  saveBounds();
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
