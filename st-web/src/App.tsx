import { lazy, Suspense, useEffect, type ReactNode } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/auth';
import { syncAuthToElectron } from './lib/electron';
import ErrorBoundary from './components/ErrorBoundary';

const Login = lazy(() => import('./pages/Login'));
const HomePage = lazy(() => import('./pages/HomePage'));
const ServerConfigPage = lazy(() => import('./pages/ServerConfigPage'));
const AppLayout = lazy(() => import('./components/layout/AppLayout'));
const FileManager = lazy(() => import('./pages/FileManager'));
const RecycleBin = lazy(() => import('./pages/RecycleBin'));
const SearchPage = lazy(() => import('./pages/SearchPage'));
const ShareManagePage = lazy(() => import('./pages/ShareManagePage'));
const ShareAccessPage = lazy(() => import('./pages/ShareAccessPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const TeamPage = lazy(() => import('./pages/TeamPage'));
const TeamSpacePage = lazy(() => import('./pages/TeamSpacePage'));
const TransferManager = lazy(() => import('./pages/TransferManager'));
const SyncPage = lazy(() => import('./pages/SyncPage'));

function Loading() {
  return (
    <div className="flex items-center justify-center h-screen bg-stone-100">
      <div className="w-8 h-8 border-3 border-primary-600 border-t-transparent rounded-full animate-spin" />
    </div>
  );
}

function ProtectedRoute({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  useEffect(() => {
    if (isAuthenticated) syncAuthToElectron();
  }, [isAuthenticated]);
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <ErrorBoundary>
      <Suspense fallback={<Loading />}>
        <Routes>
        <Route path="/login" element={<Login />} />
        {/* 公开分享访问页 - 无需登录 */}
        <Route path="/share/:shareCode" element={<ShareAccessPage />} />
        <Route path="/server-config" element={<ServerConfigPage />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <AppLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<HomePage />} />
          <Route path="files" element={<FileManager />} />
          <Route path="files/:parentId" element={<FileManager />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="recycle" element={<RecycleBin />} />
          <Route path="shares" element={<ShareManagePage />} />
          <Route path="admin" element={<AdminPage />} />
          <Route path="team" element={<TeamPage />} />
          <Route path="team/:spaceId" element={<TeamSpacePage />} />
          <Route path="transfers" element={<TransferManager />} />
          <Route path="sync" element={<SyncPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}
