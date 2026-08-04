import { useState, useRef, useEffect, type RefObject } from 'react';
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Upload, Search, ChevronRight, LogOut, User as UserIcon, Home, X } from 'lucide-react';
import { useAuthStore } from '../../store/auth';
import { useUpload } from '../../hooks/useUpload';
import { isElectron } from '../../lib/electron';
import api from '../../lib/api';
import type { FileNode } from '../../types';

export default function TopBar() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const { addFiles, addFilePaths } = useUpload();
  const [menuOpen, setMenuOpen] = useState(false);
  const [searchValue, setSearchValue] = useState('');
  const [pathChain, setPathChain] = useState<Array<{ id: string; name: string }>>([]);
  const menuRef: RefObject<HTMLDivElement> = useRef(null);
  const fileInputRef: RefObject<HTMLInputElement> = useRef(null);

  const isSearchPage = location.pathname === '/search';

  // Sync search box with URL keyword when on search page
  useEffect(() => {
    if (isSearchPage) {
      setSearchValue(searchParams.get('keyword') || '');
    } else if (!location.pathname.startsWith('/search')) {
      setSearchValue('');
    }
  }, [isSearchPage, searchParams]);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  // Fetch full parent chain for breadcrumb
  const pathSegments = location.pathname.split('/').filter(Boolean);
  const isRecycle = pathSegments[0] === 'recycle';
  const currentFolderId = pathSegments.length > 1 ? pathSegments[pathSegments.length - 1] : null;

  useEffect(() => {
    if (!currentFolderId || isRecycle) {
      setPathChain([]);
      return;
    }
    let cancelled = false;
    const fetchPathChain = async () => {
      const chain: Array<{ id: string; name: string }> = [];
      let curId: string = currentFolderId;
      let depth = 0;
      while (curId && curId !== '0' && depth < 20) {
        try {
          const node: FileNode = await api.get(`/file/${curId}`);
          if (cancelled) return;
          chain.unshift({ id: node.id, name: node.name });
          curId = node.parentId;
          depth++;
        } catch {
          break;
        }
      }
      if (!cancelled) setPathChain(chain);
    };
    fetchPathChain();
    return () => { cancelled = true; };
  }, [currentFolderId, isRecycle]);

  // Build breadcrumbs
  const breadcrumbs = isRecycle
    ? [{ label: '回收站', path: '/recycle' }]
    : [
        { label: '全部文件', path: '/files' },
        ...pathChain.map((item) => ({
          label: item.name,
          path: `/files/${item.id}`,
        })),
      ];

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    const parentId = currentFolderId || '0';
    if (files.length > 0) addFiles(files, parentId);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleUploadClick = async () => {
    const parentId = currentFolderId || '0';
    if (isElectron()) {
      const filePaths = await window.electronAPI!.selectFiles();
      if (filePaths.length > 0) {
        addFilePaths(filePaths, parentId);
      }
    } else {
      fileInputRef.current?.click();
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handleSearch = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && searchValue.trim()) {
      navigate(`/search?keyword=${encodeURIComponent(searchValue.trim())}`);
    }
  };

  return (
    <header className="h-14 bg-white border-b border-stone-200 flex items-center justify-between px-5 flex-shrink-0">
      {/* Breadcrumbs */}
      <nav className="flex items-center gap-1 text-sm min-w-0">
        <button
          onClick={() => navigate('/files')}
          className="flex items-center gap-1 text-stone-500 hover:text-stone-900 cursor-pointer"
        >
          <Home className="w-4 h-4" />
        </button>
        {isSearchPage ? (
          <div className="flex items-center gap-1 min-w-0">
            <ChevronRight className="w-4 h-4 text-stone-400 flex-shrink-0" />
            <span className="text-stone-900 font-medium truncate">搜索结果</span>
          </div>
        ) : (
          breadcrumbs.map((crumb, idx) => (
            <div key={idx} className="flex items-center gap-1 min-w-0">
              <ChevronRight className="w-4 h-4 text-stone-400 flex-shrink-0" />
              <button
                onClick={() => navigate(crumb.path)}
                className={`truncate cursor-pointer hover:text-stone-900 ${
                  idx === breadcrumbs.length - 1 ? 'text-stone-900 font-medium' : 'text-stone-500'
                }`}
              >
                {crumb.label}
              </button>
            </div>
          ))
        )}
      </nav>

      {/* Search */}
      <div className="flex-1 max-w-md mx-6">
        <div className="relative group">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-stone-400 group-focus-within:text-primary-600 transition-colors" />
          <input
            type="text"
            value={searchValue}
            onChange={(e) => setSearchValue(e.target.value)}
            onKeyDown={handleSearch}
            placeholder="搜索文件名或文档内容..."
            className="w-full pl-10 pr-9 py-1.5 bg-stone-50 border border-stone-200 rounded-md text-sm text-stone-900 placeholder-stone-400 outline-none transition-all focus:bg-white focus:border-primary-400 focus:ring-2 focus:ring-primary-100"
          />
          {searchValue && (
            <button
              onClick={() => setSearchValue('')}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 w-5 h-5 flex items-center justify-center rounded-full bg-stone-200 hover:bg-stone-300 text-stone-500 hover:text-stone-700 transition-all cursor-pointer"
              aria-label="清空搜索"
            >
              <X className="w-3.5 h-3.5" strokeWidth={2.5} />
            </button>
          )}
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-3">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleFileSelect}
        />
        <button
          onClick={handleUploadClick}
          className="btn-primary"
        >
          <Upload className="w-4 h-4" />
          <span>上传</span>
        </button>

        {/* User menu */}
        <div ref={menuRef} className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-stone-100 cursor-pointer transition-colors"
          >
            <div className="w-8 h-8 bg-primary-600 rounded-full flex items-center justify-center">
              <span className="text-sm font-medium text-white">
                {user?.username?.charAt(0).toUpperCase() || 'U'}
              </span>
            </div>
          </button>

          {menuOpen && (
            <div className="absolute right-0 top-11 w-56 bg-white rounded-lg border border-stone-200 shadow-md py-1.5 animate-scale-in z-50">
              <div className="px-4 py-2 border-b border-stone-100">
                <div className="flex items-center gap-2">
                  <UserIcon className="w-4 h-4 text-stone-400" />
                  <span className="text-sm font-medium text-stone-900">{user?.username || '用户'}</span>
                </div>
                {user?.nickname && (
                  <div className="text-xs text-stone-500 mt-1 ml-6">{user.nickname}</div>
                )}
              </div>
              <button
                onClick={handleLogout}
                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-stone-600 hover:bg-stone-100 cursor-pointer transition-colors"
              >
                <LogOut className="w-4 h-4" />
                <span>退出登录</span>
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
