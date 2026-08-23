import { app, BrowserWindow, Menu } from 'electron';
import { getAllTasks } from './database';
import { pauseUpload, resumeUpload } from './upload-manager';
import { pauseDownload, resumeDownload } from './download-manager';
import {
  getMiniWidgetMode,
  setMiniWidgetMode,
  hideMiniWindow,
  openTransferPage,
  openTransferSettings,
} from './mini-window';

/**
 * 悬浮窗右键上下文菜单：使用 Electron 原生 Menu.popup，而不是独立 HTML 小窗。
 * 原生菜单在 Windows 上处理焦点、外点关闭、置顶更可靠；首次右键即可弹出，且不会“闪两下”。
 */

const ACTIVE = new Set(['pending', 'hashing', 'uploading', 'downloading', 'merging']);

let activeMenu: Menu | null = null;

/** 判断窗口是否为菜单小窗：已改为原生菜单，不再有独立菜单窗口 */
export function isMenuWindow(_win: BrowserWindow): boolean {
  return false;
}

/** 预创建相关：原生菜单无需预加载 */
export function prepareMenuWindow(): void {
  /* no-op */
}

/** 关闭可能弹出的原生菜单 */
export function hideMenuWindow(): void {
  if (activeMenu) {
    activeMenu.closePopup();
    activeMenu = null;
  }
}

/** 主窗口关闭时的清理 */
export function closeMenuWindow(): void {
  hideMenuWindow();
}

function pauseAll(): void {
  for (const t of getAllTasks()) {
    if (ACTIVE.has(t.status)) {
      if (t.type === 'upload') pauseUpload(t.id);
      else pauseDownload(t.id);
    }
  }
}

function resumeAll(): void {
  for (const t of getAllTasks()) {
    if (t.status === 'paused') {
      if (t.type === 'upload') resumeUpload(t.id);
      else resumeDownload(t.id);
    }
  }
}

function buildMenu(): Menu {
  const all = getAllTasks();
  const hasActive = all.some((t) => ACTIVE.has(t.status));
  const hasPaused = all.some((t) => t.status === 'paused');
  const mode = getMiniWidgetMode();

  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: mode === 'expanded' ? '收起任务详情' : '展开任务详情',
      click: () => setMiniWidgetMode(mode === 'expanded' ? 'micro' : 'expanded'),
    },
    { type: 'separator' },
    {
      label: '打开传输列表',
      click: () => openTransferPage(),
    },
    {
      label: hasActive ? '暂停全部任务' : '开始全部任务',
      enabled: hasActive || hasPaused,
      click: () => (hasActive ? pauseAll() : resumeAll()),
    },
    {
      label: '传输设置',
      click: () => openTransferSettings(),
    },
    { type: 'separator' },
    {
      label: '关闭悬浮窗',
      click: () => hideMiniWindow(),
    },
    {
      label: '退出',
      click: () => app.quit(),
    },
  ];

  return Menu.buildFromTemplate(template);
}

/** 在指定窗口（悬浮窗）位置弹出原生上下文菜单 */
export function showMenuWindow(win?: BrowserWindow): void {
  // 关闭上一次可能仍打开的菜单
  hideMenuWindow();
  const menu = buildMenu();
  activeMenu = menu;
  try {
    menu.popup({ window: win });
  } catch {
    // 个别平台弹出失败时兜底，不影响悬浮窗本身
    menu.popup();
  }
}
