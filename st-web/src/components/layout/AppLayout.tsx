import { Outlet, useLocation, useNavigate } from 'react-router-dom';
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
import { isElectron } from '../../lib/electron';
import TitleBar from './TitleBar';

export default function AppLayout() {
  const { user, fetchUser } = useAuthStore();
  const fetchServerLimits = useTransferStore((s) => s.fetchServerLimits);
  const fetchFavoriteIds = useFavoritesStore((s) => s.fetchFavoriteIds);
  const [shortcutOpen, setShortcutOpen] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();

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

  // 桌面端：悬浮窗右键菜单点击"传输管理"时，主进程发消息，这里跳转传输页
  useEffect(() => {
    const unsubscribe = window.electronAPI?.onOpenTransfers?.(() => navigate('/transfers'));
    return unsubscribe;
  }, [navigate]);

  // 桌面端：悬浮窗右键菜单点击"简易限速"时，主进程发消息，跳转传输页并自动弹出设置对话框
  useEffect(() => {
    const unsubscribe = window.electronAPI?.onOpenTransferSettings?.(() => navigate('/transfers?settings=1'));
    return unsubscribe;
  }, [navigate]);

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
        {/* 侧栏整列置顶：桌面端 logo/品牌直接位于窗口顶部，填充标题栏移除后的空白 */}
        <Sidebar mobileOpen={mobileSidebarOpen} onClose={() => setMobileSidebarOpen(false)} />
        <div className="flex-1 flex flex-col min-w-0">
          {/* 标题栏仅 Electron 桌面端渲染（网页端不显示）；保留拖拽区与 Windows 三键 */}
          {isElectron() && <TitleBar />}
          <TopBar onMenuClick={() => setMobileSidebarOpen(true)} />
          {/* 左下圆角与侧栏右缘底部圆角对齐（文件区域与侧栏分割线圆角化） */}
          <main id="main-content" className="flex-1 min-h-0 overflow-hidden rounded-bl-2xl pb-20 md:pb-0">
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
