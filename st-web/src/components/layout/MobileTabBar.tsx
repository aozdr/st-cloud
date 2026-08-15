import { NavLink } from 'react-router-dom';
import { Home, FolderClosed, ArrowUpDown, Menu as MenuIcon } from 'lucide-react';
import { cn } from '../../lib/utils';

interface MobileTabBarProps {
  /** 点击"更多"触发抽屉 */
  onMoreClick: () => void;
}

/**
 * 移动端底部 Tab 导航(md 以下显示)
 * 4 项: 首页 / 文件 / 传输 / 更多(触发抽屉)
 * 传输项仅在 Capacitor/Electron 环境显示原生传输,纯浏览器显示 Web 上传进度
 */
export default function MobileTabBar({ onMoreClick }: MobileTabBarProps) {
  const tabClass = (isActive: boolean) =>
    cn(
      'flex flex-col items-center justify-center gap-0.5 flex-1 min-h-[48px] transition-colors duration-200 cursor-pointer',
      isActive ? 'text-primary-600' : 'text-muted'
    );

  return (
    <nav
      className="md:hidden fixed bottom-0 inset-x-0 z-30 bg-surface border-t border-border flex items-stretch h-16 pb-safe"
      aria-label="移动端主导航"
    >
      <NavLink to="/" end className={({ isActive }) => tabClass(isActive)}>
        <Home className="w-5 h-5" aria-hidden />
        <span className="text-[10px] font-medium">首页</span>
      </NavLink>

      <NavLink to="/files" className={({ isActive }) => tabClass(isActive)}>
        <FolderClosed className="w-5 h-5" aria-hidden />
        <span className="text-[10px] font-medium">文件</span>
      </NavLink>

      <NavLink to="/transfers" className={({ isActive }) => tabClass(isActive)}>
        <ArrowUpDown className="w-5 h-5" aria-hidden />
        <span className="text-[10px] font-medium">传输</span>
      </NavLink>

      <button
        onClick={onMoreClick}
        className={tabClass(false)}
        aria-label="更多"
      >
        <MenuIcon className="w-5 h-5" aria-hidden />
        <span className="text-[10px] font-medium">更多</span>
      </button>
    </nav>
  );
}