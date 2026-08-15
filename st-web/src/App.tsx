import { lazy, Suspense, useEffect, type ReactNode } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/auth';
import { syncAuthToElectron } from './lib/electron';
import ErrorBoundary from './components/ErrorBoundary';
import { SuspenseProgressBar } from './components/ui/TopProgressBar';

const Login = lazy(() => import('./pages/Login'));
const HomePage = lazy(() => import('./pages/HomePage'));
const ServerConfigPage = lazy(() => import('./pages/ServerConfigPage'));
const AppLayout = lazy(() => import('./components/layout/AppLayout'));
const FileManager = lazy(() => import('./pages/FileManager'));
const RecycleBin = lazy(() => import('./pages/RecycleBin'));
const SearchPage = lazy(() => import('./pages/SearchPage'));
const FavoritesPage = lazy(() => import('./pages/FavoritesPage'));
const DuplicateFilesPage = lazy(() => import('./pages/DuplicateFilesPage'));
const HiddenFilesPage = lazy(() => import('./pages/HiddenFilesPage'));
const ShareManagePage = lazy(() => import('./pages/ShareManagePage'));
const ShareAccessPage = lazy(() => import('./pages/ShareAccessPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const TeamPage = lazy(() => import('./pages/TeamPage'));
const TeamSpacePage = lazy(() => import('./pages/TeamSpacePage'));
  const TeamInvitePage = lazy(() => import('./pages/TeamInvitePage'));
const CategoryPage = lazy(() => import('./pages/CategoryPage'));
const TransferManager = lazy(() => import('./pages/TransferManager'));
const SyncPage = lazy(() => import('./pages/SyncPage'));
const EditorPage = lazy(() => import('./pages/EditorPage'));
const TextEditorPage = lazy(() => import('./pages/TextEditorPage'));

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
      <Suspense fallback={<SuspenseProgressBar />}>
        <Routes>
        <Route path="/login" element={<Login />} />
        {/* 公开分享访问页 - 无需登录 */}
        <Route path="/share/:shareCode" element={<ShareAccessPage />} />
        {/* 分享文件 OnlyOffice 查看/编辑页 - 无需登录（分享权限集决定只读/可编辑） */}
        <Route path="/share/:shareCode/editor" element={<EditorPage />} />
        <Route path="/server-config" element={<ServerConfigPage />} />
        {/* 在线文档编辑：全屏页面，位于 AppLayout 之外 */}
        <Route
          path="/file/:nodeId/editor"
          element={
            <ProtectedRoute>
              <EditorPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/file/:nodeId/text-editor"
          element={
            <ProtectedRoute>
              <TextEditorPage />
            </ProtectedRoute>
          }
        />
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
          <Route path="files/category/:type" element={<CategoryPage />} />
          <Route path="files/:parentId" element={<FileManager />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="recycle" element={<RecycleBin />} />
          <Route path="favorites" element={<FavoritesPage />} />
          <Route path="duplicates" element={<DuplicateFilesPage />} />
          <Route path="hidden" element={<HiddenFilesPage />} />
          <Route path="shares" element={<ShareManagePage />} />
          <Route path="admin" element={<AdminPage />} />
          <Route path="team" element={<TeamPage />} />
          <Route path="team/:spaceId" element={<TeamSpacePage />} />
          <Route path="team/invite/:code" element={<TeamInvitePage />} />
          <Route path="transfers" element={<TransferManager />} />
          <Route path="sync" element={<SyncPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}
