import { NavLink, useNavigate } from 'react-router-dom';
import { Cloud, FolderClosed, Trash2, Share2, Users, Settings, ArrowUpDown, Palette, FolderSync, Home, Upload, PanelLeftClose, PanelLeftOpen, X, Star, Copy, EyeOff, GripVertical } from 'lucide-react';
import { memo, useEffect, useState, useCallback } from 'react';
import { formatSize, cn } from '../../lib/utils';
import { isElectron } from '../../lib/electron';
import { usePermission } from '../../lib/permission';
import { useStorageStore } from '../../store/storage';
import SettingsDialog from '../SettingsDialog';
import { useUpload } from '../../hooks/useUpload';

interface SidebarProps {
  mobileOpen: boolean;
  onClose: () => void;
}

const COLLAPSE_KEY = 'sidebarCollapsed';
const NAV_ORDER_KEY = 'sidebarNavOrder';

interface NavItem {
  key: string;
  to: string;
  icon: typeof Trash2;
  label: string;
  end: boolean;
}

/** 默认导航顺序（受运行环境与权限影响） */
function buildNavItems(isElectronEnv: boolean, canAdmin: boolean): NavItem[] {
  return [
    { key: 'home', to: '/', icon: Home, label: '首页', end: true },
    { key: 'files', to: '/files', icon: FolderClosed, label: '全部文件', end: false },
    // Electron 专属功能紧跟「全部文件」：文件同步 → 传输管理
    ...(isElectronEnv
      ? [
          { key: 'sync', to: '/sync', icon: FolderSync, label: '文件同步', end: false },
          { key: 'transfers', to: '/transfers', icon: ArrowUpDown, label: '传输管理', end: false },
        ]
      : []),
    { key: 'favorites', to: '/favorites', icon: Star, label: '我的收藏', end: false },
    { key: 'shares', to: '/shares', icon: Share2, label: '我的分享', end: false },
    { key: 'team', to: '/team', icon: Users, label: '团队空间', end: false },
    { key: 'recycle', to: '/recycle', icon: Trash2, label: '回收站', end: false },
    { key: 'duplicates', to: '/duplicates', icon: Copy, label: '重复检测', end: false },
    { key: 'hidden', to: '/hidden', icon: EyeOff, label: '隐藏文件', end: false },
    ...(canAdmin ? [{ key: 'admin', to: '/admin', icon: Settings, label: '系统管理', end: false }] : []),
  ];
}

function loadNavOrder(): string[] {
  try {
    const raw = localStorage.getItem(NAV_ORDER_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((k): k is string => typeof k === 'string') : [];
  } catch {
    return [];
  }
}

/** 应用用户自定义顺序：已保存项按保存顺序排，新增项按默认顺序追加 */
function applyNavOrder(items: NavItem[], saved: string[]): NavItem[] {
  if (saved.length === 0) return items;
  const byKey = new Map(items.map((item) => [item.key, item]));
  const ordered: NavItem[] = [];
  const seen = new Set<string>();
  for (const key of saved) {
    const item = byKey.get(key);
    if (item && !seen.has(key)) {
      ordered.push(item);
      seen.add(key);
    }
  }
  for (const item of items) {
    if (!seen.has(item.key)) {
      ordered.push(item);
      seen.add(item.key);
    }
  }
  return ordered;
}

function Sidebar({ mobileOpen, onClose }: SidebarProps) {
  const storage = useStorageStore((s) => s.storage);
  const fetchStorage = useStorageStore((s) => s.fetchStorage);
  const { setPanelOpen, addFiles } = useUpload();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem(COLLAPSE_KEY) === '1'; } catch { return false; }
  });
  const { hasAny } = usePermission();
  const canAccessAdmin = hasAny(['admin:user:manage', 'admin:role:manage', 'admin:audit:view', 'admin:stats:view', 'transfer:speed:limit', 'admin:storage:manage']);
  const navigate = useNavigate();
  // 用户自定义导航顺序（拖拽排序，localStorage 持久化）
  const [navOrder, setNavOrder] = useState<string[]>(() => loadNavOrder());
  const [dragKey, setDragKey] = useState<string | null>(null);
  const [dragOverKey, setDragOverKey] = useState<string | null>(null);

  useEffect(() => { fetchStorage(); }, [fetchStorage]);

  const usedPercent = Math.round(storage?.percentage ?? 0);
  const isLowSpace = usedPercent > 90;
  const navItems = applyNavOrder(buildNavItems(isElectron(), canAccessAdmin), navOrder);

  const handleDragStart = useCallback((e: React.DragEvent, key: string) => {
    setDragKey(key);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', key);
  }, []);
  const handleDragOver = useCallback((e: React.DragEvent, key: string) => {
    if (!dragKey || dragKey === key) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDragOverKey(key);
  }, [dragKey]);
  const handleDrop = useCallback((e: React.DragEvent, key: string) => {
    e.preventDefault();
    const from = dragKey ?? e.dataTransfer.getData('text/plain');
    setDragKey(null);
    setDragOverKey(null);
    if (!from || from === key) return;
    const keys = navItems.map((i) => i.key);
    const fromIdx = keys.indexOf(from);
    const toIdx = keys.indexOf(key);
    if (fromIdx < 0 || toIdx < 0) return;
    const next = [...keys];
    next.splice(fromIdx, 1);
    next.splice(toIdx, 0, from);
    setNavOrder(next);
    try {
      localStorage.setItem(NAV_ORDER_KEY, JSON.stringify(next));
    } catch {
      // localStorage 不可用时仅本次会话生效
    }
  }, [dragKey, navItems]);
  const handleDragEnd = useCallback(() => {
    setDragKey(null);
    setDragOverKey(null);
  }, []);

  const handleUploadClick = useCallback(() => {
    setPanelOpen(true);
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = (e) => {
      const files = Array.from((e.target as HTMLInputElement).files || []);
      if (files.length > 0) addFiles(files, '0');
    };
    input.click();
    onClose();
  }, [setPanelOpen, addFiles, onClose]);

  const toggleCollapse = () => {
    const next = !collapsed;
    setCollapsed(next);
    try { localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0'); } catch { /* ignore */ }
  };

  const navItemClass = (isActive: boolean) =>
    cn(
      'group relative flex items-center gap-3 px-3 h-10 rounded-lg text-sm font-medium transition-colors duration-150 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
      collapsed && 'lg:justify-center lg:px-0',
      isActive
        ? 'text-primary-600 bg-[rgb(var(--nav-active-bg))] dark:bg-[rgb(var(--nav-active-bg)/0.15)] font-medium'
        : 'text-muted hover:text-fg hover:bg-white dark:hover:bg-white/10'
    );

  const widthClass = collapsed ? 'lg:w-16' : 'lg:w-60';

  return (
    <>
      {/* Mobile backdrop */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      <aside
        className={cn(
          'bg-[#F4F6FC] dark:bg-surface-2 rounded-r-2xl flex flex-col overflow-hidden flex-shrink-0',
          'fixed inset-y-0 left-0 z-50 w-60 transition-transform duration-300 lg:static lg:z-auto pt-safe pb-safe',
          widthClass,
          mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
        aria-label="侧边导航"
      >
        {/* Logo：在侧栏顶部居中展示，放大填充原品牌位置，略微下移留出呼吸空间 */}
        <div className="mt-4 h-12 mb-6 relative flex-shrink-0 flex items-center">
          <div className="flex items-center justify-center gap-3 flex-1 min-w-0">
            <div className="w-10 h-10 bg-primary-600 rounded-[10px] flex items-center justify-center flex-shrink-0">
              <Cloud className="w-6 h-6 text-white" aria-hidden />
            </div>
            {!collapsed && <span className="text-xl font-semibold text-fg tracking-tight hidden lg:block">星云盘</span>}
            <span className="text-xl font-semibold text-fg tracking-tight lg:hidden">星云盘</span>
          </div>
          {/* Mobile close */}
          <button
            onClick={onClose}
            aria-label="关闭侧边栏"
            className="absolute right-2 lg:hidden text-muted hover:text-fg p-1 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        {/* Upload button */}
        <div className="px-3 pb-4">
          <button
            onClick={handleUploadClick}
            aria-label="上传文件"
            className={cn(
              'w-full flex items-center justify-center gap-2 h-10 px-4 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 active:bg-primary-800 transition-colors duration-150 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
              collapsed && 'lg:px-0'
            )}
          >
            <Upload className="w-4 h-4 flex-shrink-0" aria-hidden />
            {!collapsed && <span className="hidden lg:inline">上传文件</span>}
            <span className="lg:hidden">上传文件</span>
          </button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 px-3 overflow-y-auto scrollbar-hide" aria-label="主导航">
          {navItems.map((item) => (
            <NavLink
              key={item.key}
              to={item.to}
              end={item.end}
              draggable={!collapsed}
              onDragStart={(e) => handleDragStart(e, item.key)}
              onDragOver={(e) => handleDragOver(e, item.key)}
              onDrop={(e) => handleDrop(e, item.key)}
              onDragEnd={handleDragEnd}
              className={({ isActive }) => cn(
                navItemClass(isActive),
                dragOverKey === item.key && 'ring-2 ring-primary-400 bg-surface',
                dragKey === item.key && 'opacity-50',
              )}
              onClick={onClose}
              title={collapsed ? item.label : undefined}
            >
              {({ isActive }) => (
                <>
                  <item.icon className={cn('w-5 h-5 flex-shrink-0 transition-colors', isActive ? 'text-primary-600' : 'text-muted group-hover:text-fg')} aria-hidden />
                  {!collapsed && <span className="hidden lg:inline">{item.label}</span>}
                  <span className="lg:hidden">{item.label}</span>
                  {/* 拖拽排序提示：悬停显示抓手图标 */}
                  {!collapsed && (
                    <GripVertical className="w-3.5 h-3.5 text-muted/40 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0 ml-auto" aria-hidden />
                  )}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {/* Storage summary（UI_DESIGN_SPEC：线性进度卡） */}
        <div className="px-3 py-3 flex-shrink-0 border-t border-white/5">
          <button
            onClick={toggleCollapse}
            aria-label={collapsed ? '展开侧边栏' : '折叠侧边栏'}
            className={cn('hidden lg:w-full lg:flex items-center gap-3 px-3 h-9 rounded-lg text-[13px] font-medium text-muted hover:text-fg hover:bg-white dark:hover:bg-white/10 transition-colors duration-150 cursor-pointer mb-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed && 'lg:justify-center lg:px-0')}
            title={collapsed ? '展开侧边栏' : undefined}
          >
            {collapsed ? <PanelLeftOpen className="w-[18px] h-[18px] flex-shrink-0" aria-hidden /> : <PanelLeftClose className="w-[18px] h-[18px] flex-shrink-0" aria-hidden />}
            {!collapsed && <span className="hidden lg:inline">折叠侧栏</span>}
          </button>

          <button
            onClick={() => { setSettingsOpen(true); onClose(); }}
            aria-label="主题设置"
            className={cn('w-full flex items-center gap-3 px-3 h-9 rounded-lg text-[13px] font-medium text-muted hover:text-fg hover:bg-white dark:hover:bg-white/10 transition-colors duration-150 cursor-pointer mb-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed && 'lg:justify-center lg:px-0')}
            title={collapsed ? '主题设置' : undefined}
          >
            <Palette className="w-[18px] h-[18px] flex-shrink-0" aria-hidden />
            {!collapsed && <span className="hidden lg:inline">主题设置</span>}
            <span className="lg:hidden">主题设置</span>
          </button>

          <div
            onClick={() => { navigate('/files'); onClose(); }}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate('/files'); onClose(); } }}
            className={cn('bg-surface dark:bg-white/10 rounded-xl cursor-pointer hover:bg-bg-hover dark:hover:bg-white/15 transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed ? 'lg:bg-transparent lg:hover:bg-transparent lg:rounded-none lg:p-0 lg:flex lg:items-center lg:justify-center' : 'p-4')}
            title={collapsed ? `已用 ${usedPercent}% · ${formatSize(Number(storage?.quota || 0) - Number(storage?.used || 0))} 可用` : undefined}
          >
            {collapsed ? (
              <span className="lg:flex lg:items-center lg:justify-center w-9 h-9 text-xs font-bold text-fg tabular-nums">{usedPercent}%</span>
            ) : (
              <div className="min-w-0">
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-xs font-medium text-fg">存储空间</span>
                  <span className={cn('text-xs tabular-nums', isLowSpace ? 'text-danger font-medium' : 'text-tertiary')}>
                    {isLowSpace ? '空间不足' : `${usedPercent}%`}
                  </span>
                </div>
                <div className="h-1.5 bg-[#E7EAF1] dark:bg-white/20 rounded-full overflow-hidden">
                  <div
                    className={cn('h-full rounded-full transition-[width] duration-500', isLowSpace ? 'bg-danger' : 'bg-primary-600')}
                    style={{ width: `${Math.min(usedPercent, 100)}%` }}
                  />
                </div>
                <div className="mt-1.5 text-xs text-tertiary tabular-nums truncate">
                  {formatSize(storage?.used)} / {formatSize(storage?.quota)} · {formatSize(Number(storage?.quota || 0) - Number(storage?.used || 0))} 可用
                </div>
              </div>
            )}
          </div>
        </div>
      </aside>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}

export default memo(Sidebar);
