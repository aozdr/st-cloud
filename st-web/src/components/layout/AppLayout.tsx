import { Outlet } from 'react-router-dom';
import { useEffect, useState } from 'react';
import Sidebar from './Sidebar';
import TopBar from './TopBar';
import ShortcutHelpDialog from '../ui/ShortcutHelpDialog';
import { useAuthStore } from '../../store/auth';
import { useTransferStore } from '../../store/transfer';
import { UploadProvider } from '../../hooks/useUpload';

export default function AppLayout() {
  const { user, fetchUser } = useAuthStore();
  const fetchServerLimits = useTransferStore((s) => s.fetchServerLimits);
  const [shortcutOpen, setShortcutOpen] = useState(false);

  useEffect(() => {
    if (!user) {
      fetchUser();
    } else {
      fetchServerLimits();
    }
  }, [user, fetchUser, fetchServerLimits]);

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
      <div className="flex h-screen overflow-hidden bg-stone-50">
        <Sidebar />
        <div className="flex-1 flex flex-col min-w-0">
          <TopBar />
          <main className="flex-1 overflow-auto">
            <Outlet />
          </main>
        </div>
      </div>
      <ShortcutHelpDialog open={shortcutOpen} onClose={() => setShortcutOpen(false)} />
    </UploadProvider>
  );
}