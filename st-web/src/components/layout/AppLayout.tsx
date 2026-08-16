import { Outlet, useLocation } from 'react-router-dom';
import { Suspense, useEffect, useState } from 'react';
import Sidebar from './Sidebar';
import TopBar from './TopBar';
import MobileTabBar from './MobileTabBar';
import PwaInstallBanner from './PwaInstallBanner';
import ShortcutHelpDialog from '../ui/ShortcutHelpDialog';
import { SuspenseProgressBar } from '../ui/TopProgressBar';
import { useAuthStore } from '../../store/auth';
import { useTransferStore } from '../../store/transfer';
import { useFavoritesStore } from '../../store/favorites';
import { UploadProvider } from '../../hooks/useUpload';

export default function AppLayout() {
  const { user, fetchUser } = useAuthStore();
  const fetchServerLimits = useTransferStore((s) => s.fetchServerLimits);
  const fetchFavoriteIds = useFavoritesStore((s) => s.fetchFavoriteIds);
  const [shortcutOpen, setShortcutOpen] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const location = useLocation();

  useEffect(() => {
    if (!user) {
      fetchUser();
    } else {
      fetchServerLimits();
      // 登录后加载收藏ID列表，供文件浏览器判断收藏状态
      fetchFavoriteIds();
    }
  }, [user, fetchUser, fetchServerLimits, fetchFavoriteIds]);

  // Close mobile sidebar on route change
  useEffect(() => {
    setMobileSidebarOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === '?' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const target = e.target as HTMLElement;
        if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;
        e.preventDefault();
        setShortcutOpen((prev) => !prev);
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  return (
    <UploadProvider>
      <div className="flex h-screen overflow-hidden bg-bg">
        <Sidebar mobileOpen={mobileSidebarOpen} onClose={() => setMobileSidebarOpen(false)} />
        <div className="flex-1 flex flex-col min-w-0">
          <TopBar onMenuClick={() => setMobileSidebarOpen(true)} />
          <main id="main-content" className="flex-1 min-h-0 overflow-hidden pb-20 md:pb-0">
            {/* Suspense 仅包裹 Outlet：路由切换时侧边栏/顶栏不重新挂载，仅顶部进度条提示。
                不按 pathname 加 key：打开文件夹时页面不整页重挂载，只有文件列表原地更新（Windows 风格） */}
            <Suspense fallback={<SuspenseProgressBar />}>
              <div className="h-full">
                <Outlet />
              </div>
            </Suspense>
          </main>
        </div>
      </div>
      <MobileTabBar onMoreClick={() => setMobileSidebarOpen(true)} />
      <PwaInstallBanner />
      <ShortcutHelpDialog open={shortcutOpen} onClose={() => setShortcutOpen(false)} />
    </UploadProvider>
  );
}
