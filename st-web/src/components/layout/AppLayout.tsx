import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import Sidebar from './Sidebar';
import TopBar from './TopBar';
import { useAuthStore } from '../../store/auth';
import { useTransferStore } from '../../store/transfer';
import { UploadProvider } from '../../hooks/useUpload';

export default function AppLayout() {
  const { user, fetchUser } = useAuthStore();
  const fetchServerLimits = useTransferStore((s) => s.fetchServerLimits);

  useEffect(() => {
    if (!user) {
      fetchUser();
    } else {
      fetchServerLimits();
    }
  }, [user, fetchUser, fetchServerLimits]);

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
    </UploadProvider>
  );
}
