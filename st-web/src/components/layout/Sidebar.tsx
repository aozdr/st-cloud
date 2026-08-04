import { NavLink } from 'react-router-dom';
import { Cloud, FolderClosed, Trash2, HardDrive, Share2, Users, Settings, ArrowUpDown, Palette, FolderSync } from 'lucide-react';
import { useEffect, useState } from 'react';
import { formatSize } from '../../lib/utils';
import { isElectron } from '../../lib/electron';
import { useAuthStore } from '../../store/auth';
import { useStorageStore } from '../../store/storage';
import SettingsDialog from '../SettingsDialog';

export default function Sidebar() {
  const storage = useStorageStore((s) => s.storage);
  const fetchStorage = useStorageStore((s) => s.fetchStorage);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const isAdmin = useAuthStore((s) => s.user?.isAdmin ?? false);

  useEffect(() => {
    fetchStorage();
  }, [fetchStorage]);

  const usedPercent = storage?.percentage ?? 0;
  const isLowSpace = usedPercent > 90;

  const navItems = [
    { to: '/files', icon: FolderClosed, label: '全部文件', end: false },
    { to: '/shares', icon: Share2, label: '分享管理', end: false },
    { to: '/team', icon: Users, label: '团队空间', end: false },
    { to: '/recycle', icon: Trash2, label: '回收站', end: false },
  ];

  // Electron 模式下增加传输管理与文件同步
  if (isElectron()) {
    navItems.push({ to: '/transfers', icon: ArrowUpDown, label: '传输管理', end: false });
    navItems.push({ to: '/sync', icon: FolderSync, label: '文件同步', end: false });
  }

  if (isAdmin) {
    navItems.push({ to: '/admin', icon: Settings, label: '系统管理', end: false });
  }

  return (
    <aside className="w-60 flex-shrink-0 bg-stone-900 flex flex-col overflow-hidden">
      {/* Logo */}
      <div className="h-14 flex items-center gap-2.5 px-5 border-b border-stone-800 flex-shrink-0">
        <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
          <Cloud className="w-5 h-5 text-white" />
        </div>
        <span className="text-base font-semibold text-white tracking-tight">星云盘</span>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-2 py-4 space-y-0.5 overflow-y-auto">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              `flex items-center gap-3 px-4 py-2.5 rounded-md text-sm font-medium transition-colors duration-150 cursor-pointer ${
                isActive
                  ? 'text-white bg-stone-800 border-l-[3px] border-primary-600'
                  : 'text-stone-400 hover:text-white hover:bg-stone-800'
              }`
            }
          >
            <item.icon className="w-4 h-4 flex-shrink-0" />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>

      {/* Settings + Storage usage */}
      <div className="p-4 border-t border-stone-800 flex-shrink-0">
        <button
          onClick={() => setSettingsOpen(true)}
          className="w-full flex items-center gap-3 px-4 py-2.5 rounded-md text-sm font-medium text-stone-300 hover:text-white hover:bg-stone-800 transition-colors duration-150 cursor-pointer mb-3"
        >
          <Palette className="w-4 h-4 flex-shrink-0" />
          <span>主题设置</span>
        </button>

        <div className="flex items-center gap-2 mb-2">
          <HardDrive className="w-4 h-4 text-stone-500" />
          <span className="text-xs font-medium text-stone-400">存储空间</span>
        </div>
        <div className="w-full h-1.5 bg-stone-800 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ${
              isLowSpace ? 'bg-red-500' : 'bg-primary-600'
            }`}
            style={{ width: `${Math.min(usedPercent, 100)}%` }}
          />
        </div>
        <div className="mt-2 text-xs text-stone-500">
          {formatSize(storage?.used)} / {formatSize(storage?.quota)}
        </div>
        {isLowSpace && (
          <div className="mt-1.5 text-xs text-red-400 font-medium">空间不足，请清理文件</div>
        )}
      </div>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </aside>
  );
}
