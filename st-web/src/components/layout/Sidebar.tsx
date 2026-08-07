import { NavLink, useNavigate } from 'react-router-dom';
import { Cloud, FolderClosed, Trash2, Share2, Users, Settings, ArrowUpDown, Palette, FolderSync, Home, Upload, ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { formatSize, cn } from '../../lib/utils';
import { isElectron } from '../../lib/electron';
import { usePermission } from '../../lib/permission';
import { useStorageStore } from '../../store/storage';
import SettingsDialog from '../SettingsDialog';
import { useUpload } from '../../hooks/useUpload';

export default function Sidebar() {
  const storage = useStorageStore((s) => s.storage);
  const fetchStorage = useStorageStore((s) => s.fetchStorage);
    const { setPanelOpen, addFiles } = useUpload();
  const [settingsOpen, setSettingsOpen] = useState(false);
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
    { to: '/shares', icon: Share2, label: '我的分享', end: false },
    { to: '/team', icon: Users, label: '团队空间', end: false },
  ];

  const toolNav = [
    { to: '/recycle', icon: Trash2, label: '回收站', end: false },
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
  };

  const navItemClass = (isActive: boolean) =>
    cn(
      'group relative flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-medium transition-all duration-200 cursor-pointer',
      isActive
        ? 'text-white bg-primary-600/15'
        : 'text-stone-400 hover:text-white hover:bg-white/5'
    );

  return (
    <aside className="w-64 flex-shrink-0 sidebar-gradient flex flex-col overflow-hidden">
      {/* Logo */}
      <div className="h-14 flex items-center gap-2.5 px-5 flex-shrink-0">
        <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-lg flex items-center justify-center shadow-primary">
          <Cloud className="w-5 h-5 text-white" aria-hidden />
        </div>
        <span className="text-base font-semibold text-white tracking-tight">星云盘</span>
      </div>

      {/* Upload button */}
      <div className="px-3 pb-3">
        <button
          onClick={handleUploadClick}
          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-gradient-to-r from-primary-600 to-primary-500 text-white text-sm font-medium rounded-lg hover:from-primary-500 hover:to-primary-400 transition-all duration-200 cursor-pointer shadow-primary"
        >
          <Upload className="w-4 h-4" aria-hidden />
          <span>上传文件</span>
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 overflow-y-auto">
        <div className="px-3 py-2 text-[10px] font-semibold uppercase tracking-wider text-stone-600">主菜单</div>
        {mainNav.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => navItemClass(isActive)}>
            {({ isActive }) => (
              <>
                {isActive && <span className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-[3px] bg-primary-500 rounded-r-full" />}
                <item.icon className={cn('w-[18px] h-[18px] flex-shrink-0 transition-colors', isActive ? 'text-primary-400' : 'text-stone-500 group-hover:text-stone-300')} aria-hidden />
                <span>{item.label}</span>
              </>
            )}
          </NavLink>
        ))}

        <div className="px-3 py-2 mt-3 text-[10px] font-semibold uppercase tracking-wider text-stone-600">工具</div>
        {toolNav.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => navItemClass(isActive)}>
            {({ isActive }) => (
              <>
                {isActive && <span className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-[3px] bg-primary-500 rounded-r-full" />}
                <item.icon className={cn('w-[18px] h-[18px] flex-shrink-0 transition-colors', isActive ? 'text-primary-400' : 'text-stone-500 group-hover:text-stone-300')} aria-hidden />
                <span>{item.label}</span>
              </>
            )}
          </NavLink>
        ))}
      </nav>

      {/* Storage widget with ring */}
      <div className="px-3 py-3 flex-shrink-0 border-t border-white/5">
        <button
          onClick={() => setSettingsOpen(true)}
          className="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] font-medium text-stone-400 hover:text-white hover:bg-white/5 transition-all duration-200 cursor-pointer mb-2"
        >
          <Palette className="w-[18px] h-[18px] flex-shrink-0" aria-hidden />
          <span>主题设置</span>
        </button>

        <div
          onClick={() => navigate('/files')}
          className="glass rounded-xl p-3 cursor-pointer hover:bg-white/10 transition-all duration-200"
        >
          <div className="flex items-center gap-3">
            <div className="relative flex-shrink-0">
              <svg width="64" height="64" viewBox="0 0 64 64" className="-rotate-90">
                <circle cx="32" cy="32" r={radius} fill="none" stroke="rgba(255,255,255,0.1)" strokeWidth="5" />
                <circle
                  cx="32" cy="32" r={radius} fill="none"
                  stroke={isLowSpace ? '#ef4444' : 'rgb(217 39 46)'}
                  strokeWidth="5" strokeLinecap="round"
                  strokeDasharray={circ} strokeDashoffset={dashOffset}
                  className="transition-all duration-700"
                />
              </svg>
              <div className="absolute inset-0 flex items-center justify-center">
                <span className="text-xs font-bold text-white tabular-nums">{usedPercent}%</span>
              </div>
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-medium text-stone-300">存储空间</div>
              <div className="text-[11px] text-stone-500 mt-0.5 tabular-nums">{formatSize(storage?.used)} / {formatSize(storage?.quota)}</div>
              {isLowSpace
                ? <div className="text-[11px] text-red-400 font-medium mt-0.5">空间不足</div>
                : <div className="text-[11px] text-stone-500 mt-0.5">{formatSize(Number(storage?.quota || 0) - Number(storage?.used || 0))} 可用</div>
              }
            </div>
            <ChevronRight className="w-4 h-4 text-stone-600 flex-shrink-0" aria-hidden />
          </div>
        </div>
      </div>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </aside>
  );
}
