import { NavLink, useNavigate } from 'react-router-dom';
import { Cloud, FolderClosed, Trash2, Share2, Users, Settings, ArrowUpDown, Palette, FolderSync, Home, Upload, ChevronRight, PanelLeftClose, PanelLeftOpen, X, Star, Copy, EyeOff } from 'lucide-react';
import { useEffect, useState } from 'react';
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

export default function Sidebar({ mobileOpen, onClose }: SidebarProps) {
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

  useEffect(() => { fetchStorage(); }, [fetchStorage]);

  const usedPercent = Math.round(storage?.percentage ?? 0);
  const isLowSpace = usedPercent > 90;
  const radius = 28;
  const circ = 2 * Math.PI * radius;
  const dashOffset = circ - (Math.min(usedPercent, 100) / 100) * circ;

  const mainNav = [
    { to: '/', icon: Home, label: '首页', end: true },
    { to: '/files', icon: FolderClosed, label: '全部文件', end: false },
    { to: '/favorites', icon: Star, label: '我的收藏', end: false },
    { to: '/shares', icon: Share2, label: '我的分享', end: false },
    { to: '/team', icon: Users, label: '团队空间', end: false },
  ];

  const toolNav: { to: string; icon: typeof Trash2; label: string; end: boolean }[] = [
    { to: '/recycle', icon: Trash2, label: '回收站', end: false },
    { to: '/duplicates', icon: Copy, label: '重复检测', end: false },
    { to: '/hidden', icon: EyeOff, label: '隐藏文件', end: false },
  ];

  if (isElectron()) {
    toolNav.push({ to: '/transfers', icon: ArrowUpDown, label: '传输管理', end: false });
    toolNav.push({ to: '/sync', icon: FolderSync, label: '文件同步', end: false });
  }

  if (canAccessAdmin) {
    toolNav.push({ to: '/admin', icon: Settings, label: '系统管理', end: false });
  }

  const handleUploadClick = () => {
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
  };

  const toggleCollapse = () => {
    const next = !collapsed;
    setCollapsed(next);
    try { localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0'); } catch { /* ignore */ }
  };

  const navItemClass = (isActive: boolean) =>
    cn(
      'group relative flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-medium transition-colors duration-200 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
      collapsed && 'lg:justify-center lg:px-0',
      isActive
        ? 'text-primary-600 bg-[rgb(var(--nav-active-bg))]'
        : 'text-muted hover:text-fg hover:bg-surface'
    );

  const widthClass = collapsed ? 'lg:w-16' : 'lg:w-64';

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
          'bg-surface-2 border-r border-border flex flex-col overflow-hidden flex-shrink-0',
          'fixed inset-y-0 left-0 z-50 w-64 transition-transform duration-300 lg:static lg:z-auto pt-safe pb-safe',
          widthClass,
          mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
        aria-label="侧边导航"
      >
        {/* Logo */}
        <div className="h-14 flex items-center gap-2.5 px-5 flex-shrink-0" style={collapsed ? { paddingLeft: undefined, justifyContent: 'center' } : undefined}>
          <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-lg flex items-center justify-center shadow-primary flex-shrink-0">
            <Cloud className="w-5 h-5 text-white" aria-hidden />
          </div>
          {!collapsed && <span className="text-base font-semibold text-fg tracking-tight hidden lg:block">星云盘</span>}
          <span className="text-base font-semibold text-fg tracking-tight lg:hidden">星云盘</span>
          {/* Mobile close */}
          <button
            onClick={onClose}
            aria-label="关闭侧边栏"
            className="ml-auto lg:hidden text-muted hover:text-fg p-1 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        {/* Upload button */}
        <div className="px-3 pb-3">
          <button
            onClick={handleUploadClick}
            aria-label="上传文件"
            className={cn(
              'w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gradient-to-r from-primary-600 to-primary-500 text-white text-sm font-medium rounded-lg hover:from-primary-500 hover:to-primary-400 transition-colors duration-200 cursor-pointer shadow-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
              collapsed && 'lg:px-0'
            )}
          >
            <Upload className="w-4 h-4 flex-shrink-0" aria-hidden />
            {!collapsed && <span className="hidden lg:inline">上传文件</span>}
            <span className="lg:hidden">上传文件</span>
          </button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 px-3 overflow-y-auto" aria-label="主导航">
          {!collapsed && <div className="px-3 py-2 text-[10px] font-semibold uppercase tracking-wider text-muted/70 hidden lg:block">主菜单</div>}
          {mainNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              aria-current="page"
              className={({ isActive }) => navItemClass(isActive)}
              onClick={onClose}
              title={collapsed ? item.label : undefined}
            >
              {({ isActive }) => (
                <>
                  {isActive && <span className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-[3px] bg-primary-500 rounded-r-full" />}
                  <item.icon className={cn('w-[18px] h-[18px] flex-shrink-0 transition-colors', isActive ? 'text-primary-600' : 'text-muted group-hover:text-fg')} aria-hidden />
                  {!collapsed && <span className="hidden lg:inline">{item.label}</span>}
                  <span className="lg:hidden">{item.label}</span>
                </>
              )}
            </NavLink>
          ))}

          {!collapsed && <div className="px-3 py-2 mt-3 text-[10px] font-semibold uppercase tracking-wider text-muted/70 hidden lg:block">工具</div>}
          {toolNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => navItemClass(isActive)}
              onClick={onClose}
              title={collapsed ? item.label : undefined}
            >
              {({ isActive }) => (
                <>
                  {isActive && <span className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-[3px] bg-primary-500 rounded-r-full" />}
                  <item.icon className={cn('w-[18px] h-[18px] flex-shrink-0 transition-colors', isActive ? 'text-primary-600' : 'text-muted group-hover:text-fg')} aria-hidden />
                  {!collapsed && <span className="hidden lg:inline">{item.label}</span>}
                  <span className="lg:hidden">{item.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {/* Storage widget with ring */}
        <div className="px-3 py-3 flex-shrink-0 border-t border-white/5">
          <button
            onClick={toggleCollapse}
            aria-label={collapsed ? '展开侧边栏' : '折叠侧边栏'}
            className={cn('hidden lg:w-full lg:flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] font-medium text-muted hover:text-fg hover:bg-surface transition-colors duration-200 cursor-pointer mb-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed && 'lg:justify-center lg:px-0')}
            title={collapsed ? '展开侧边栏' : undefined}
          >
            {collapsed ? <PanelLeftOpen className="w-[18px] h-[18px] flex-shrink-0" aria-hidden /> : <PanelLeftClose className="w-[18px] h-[18px] flex-shrink-0" aria-hidden />}
            {!collapsed && <span className="hidden lg:inline">折叠侧栏</span>}
          </button>

          <button
            onClick={() => { setSettingsOpen(true); onClose(); }}
            aria-label="主题设置"
            className={cn('w-full flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] font-medium text-muted hover:text-fg hover:bg-surface transition-colors duration-200 cursor-pointer mb-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed && 'lg:justify-center lg:px-0')}
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
            className={cn('bg-surface border border-border rounded-xl cursor-pointer hover:bg-surface-2 transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', collapsed ? 'lg:bg-transparent lg:hover:bg-transparent lg:rounded-none lg:p-0 lg:flex lg:items-center lg:justify-center' : 'p-3')}
            title={collapsed ? `已用 ${usedPercent}% · ${formatSize(Number(storage?.quota || 0) - Number(storage?.used || 0))} 可用` : undefined}
          >
            <div className={cn('flex items-center', collapsed && 'lg:gap-0', !collapsed && 'gap-3')}>
              <div className={cn('relative flex-shrink-0', collapsed ? 'lg:w-9 lg:h-9' : 'w-16 h-16')}>
                <svg
                  width={collapsed ? 36 : 64}
                  height={collapsed ? 36 : 64}
                  viewBox="0 0 64 64"
                  className="-rotate-90 lg:block"
                  aria-hidden
                >
                  <circle cx="32" cy="32" r={radius} fill="none" stroke="rgb(var(--border))" strokeWidth="5" />
                  <circle
                    cx="32" cy="32" r={radius} fill="none"
                    stroke={isLowSpace ? '#ef4444' : 'rgb(var(--color-primary-500))'}
                    strokeWidth="5" strokeLinecap="round"
                    strokeDasharray={circ} strokeDashoffset={dashOffset}
                    className="transition-[stroke-dashoffset] duration-700"
                  />
                </svg>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className={cn('font-bold text-fg tabular-nums', collapsed ? 'lg:text-[9px]' : 'text-xs')}>{usedPercent}%</span>
                </div>
              </div>
              {!collapsed && (
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-medium text-fg">存储空间</div>
                  <div className="text-[11px] text-muted mt-0.5 tabular-nums">{formatSize(storage?.used)} / {formatSize(storage?.quota)}</div>
                  {isLowSpace
                    ? <div className="text-[11px] text-red-400 font-medium mt-0.5">空间不足</div>
                    : <div className="text-[11px] text-muted mt-0.5">{formatSize(Number(storage?.quota || 0) - Number(storage?.used || 0))} 可用</div>
                  }
                </div>
              )}
              {!collapsed && <ChevronRight className="w-4 h-4 text-muted flex-shrink-0" aria-hidden />}
            </div>
          </div>
        </div>
      </aside>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}
