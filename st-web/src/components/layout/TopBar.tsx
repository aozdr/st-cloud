import { useState, useRef, useEffect, type RefObject } from 'react';
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Search, LogOut, User as UserIcon, X, Home, Clock, Trash2, FolderOpen } from 'lucide-react';
import { useAuthStore } from '../../store/auth';
import { useFolderFilterStore } from '../../store/folderFilter';
import { cn } from '../../lib/utils';

const SEARCH_HISTORY_KEY = 'searchHistory';
const MAX_HISTORY = 8;

function loadHistory(): string[] {
  try {
    return JSON.parse(localStorage.getItem(SEARCH_HISTORY_KEY) || '[]');
  } catch {
    return [];
  }
}

function saveHistory(keyword: string): string[] {
  const prev = loadHistory();
  const updated = [keyword, ...prev.filter(h => h !== keyword)].slice(0, MAX_HISTORY);
  localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(updated));
  return updated;
}

export default function TopBar() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [menuOpen, setMenuOpen] = useState(false);
  const [searchValue, setSearchValue] = useState('');
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [searchInFolder, setSearchInFolder] = useState(false);
  const setFolderFilter = useFolderFilterStore((s) => s.setKeyword);
  const menuRef: RefObject<HTMLDivElement> = useRef(null);
  const searchRef: RefObject<HTMLDivElement> = useRef(null);
  const inputRef: RefObject<HTMLInputElement> = useRef(null);

  const isSearchPage = location.pathname === '/search';
  const isFilesPage = location.pathname.startsWith('/files');
  const isMac = typeof navigator !== 'undefined' && navigator.platform.toUpperCase().includes('MAC');

  useEffect(() => {
    setSearchHistory(loadHistory());
  }, []);

  useEffect(() => {
    if (isSearchPage) {
      setSearchValue(searchParams.get('keyword') || '');
    } else if (!location.pathname.startsWith('/search')) {
      setSearchValue('');
      setFolderFilter('');
    }
  }, [isSearchPage, searchParams, location.pathname]);

  // Ctrl+K / Cmd+K to focus search
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  // Close menus on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
        setShowHistory(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const executeSearch = (kw: string) => {
    const trimmed = kw.trim();
    if (!trimmed) return;
    if (searchInFolder && isFilesPage) {
      setFolderFilter(trimmed);
      setShowHistory(false);
      return;
    }
    setSearchHistory(saveHistory(trimmed));
    setFolderFilter('');
    setShowHistory(false);
    navigate(`/search?keyword=${encodeURIComponent(trimmed)}&_t=${Date.now()}`);
  };

  const handleSearch = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && searchValue.trim()) {
      executeSearch(searchValue);
    }
  };

  const clearHistory = () => {
    localStorage.removeItem(SEARCH_HISTORY_KEY);
    setSearchHistory([]);
  };

  return (
    <header className="h-14 bg-white border-b border-stone-100 flex items-center px-5 flex-shrink-0 gap-4 shadow-soft">
      {/* Home */}
      <button
        onClick={() => navigate('/')}
        className="w-9 h-9 flex items-center justify-center rounded-full text-stone-400 hover:text-primary-600 hover:bg-primary-50 cursor-pointer transition-all duration-200 flex-shrink-0"
        title="回到首页"
      >
        <Home className="w-4 h-4" aria-hidden />
        
      </button>

      {/* Search */}
      <div className="flex-1 flex justify-center">
        <div className="relative w-full max-w-2xl group" ref={searchRef}>
          <div className="flex items-center bg-stone-50 border border-stone-200 rounded-full transition-all duration-200 group-focus-within:bg-white group-focus-within:border-primary-300 group-focus-within:ring-4 group-focus-within:ring-primary-100/50">
            <div className="relative flex-1">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-stone-400 group-focus-within:text-primary-500 transition-colors" aria-hidden />
              <input
                ref={inputRef}
                type="text"
                value={searchValue}
                onChange={(e) => { setSearchValue(e.target.value); if (searchInFolder && isFilesPage) setFolderFilter(e.target.value); }}
                onKeyDown={handleSearch}
                onFocus={() => setShowHistory(true)}
                aria-label="搜索"
                placeholder="搜索文件名或文档内容..."
                className="w-full pl-11 pr-3 py-2 bg-transparent border-0 text-sm text-stone-900 placeholder-stone-400 outline-none rounded-full"
              />
            </div>
            <div className="flex items-center pr-1">
              {searchValue ? (
                <button
                  onClick={() => { setSearchValue(''); inputRef.current?.focus(); }}
                  className="w-6 h-6 flex items-center justify-center rounded-full text-stone-400 hover:text-stone-600 hover:bg-stone-200/60 transition-all duration-150 cursor-pointer mr-1"
                  aria-label="清空搜索"
                >
                  <X className="w-3.5 h-3.5" strokeWidth={2.5} aria-hidden />
                </button>
              ) : (
                <kbd className="hidden sm:flex items-center gap-0.5 px-1.5 py-0.5 mr-2 text-[10px] font-medium text-stone-400 bg-stone-100 border border-stone-200 rounded">
                  {isMac ? '⌘' : 'Ctrl'} K
                </kbd>
              )}
              <button
                onClick={() => executeSearch(searchValue)}
                className="flex items-center gap-1.5 px-4 py-1.5 bg-primary-600 text-white text-sm font-medium rounded-full hover:bg-primary-700 active:bg-primary-800 cursor-pointer transition-all duration-200 flex-shrink-0"
              >
                <Search className="w-4 h-4" aria-hidden />
                <span>搜索</span>
              </button>
            </div>
          </div>

          {/* Unified search dropdown: history list on top, folder toggle pinned at bottom */}
          {showHistory && (
            <div className="absolute top-full left-0 right-0 mt-1.5 bg-white rounded-2xl border border-stone-100 z-50 animate-scale-in shadow-float overflow-hidden flex flex-col max-h-80">
              {!searchValue && searchHistory.length > 0 && (
                <>
                  <div className="flex items-center justify-between px-3 py-1.5 flex-shrink-0">
                    <span className="text-xs font-medium text-stone-400">最近搜索</span>
                    <button
                      onClick={clearHistory}
                      className="flex items-center gap-1 text-xs text-stone-400 hover:text-stone-600 cursor-pointer transition-colors"
                    >
                      <Trash2 className="w-3 h-3" aria-hidden />
                      <span>清除</span>
                    </button>
                  </div>
                  <div className="overflow-y-auto">
                    {searchHistory.map((kw, idx) => (
                      <button
                        key={idx}
                        onClick={() => executeSearch(kw)}
                        className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-stone-600 hover:bg-stone-50 hover:text-primary-600 cursor-pointer transition-colors"
                      >
                        <Clock className="w-3.5 h-3.5 text-stone-400 flex-shrink-0" aria-hidden />
                        <span className="truncate">{kw}</span>
                      </button>
                    ))}
                  </div>
                </>
              )}
              {isFilesPage && (
                <div className="border-t border-stone-100 px-3 py-2 flex-shrink-0">
                  <label className="flex items-center justify-between gap-2 cursor-pointer">
                    <span className="flex items-center gap-2 text-sm text-stone-600">
                      <FolderOpen className="w-4 h-4 text-stone-400" aria-hidden />
                      搜索当前文件夹
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        const next = !searchInFolder;
                        setSearchInFolder(next);
                        if (!next) { setFolderFilter(''); setSearchValue(''); }
                      }}
                      className={cn('relative w-9 h-5 rounded-full transition-colors cursor-pointer', searchInFolder ? 'bg-primary-600' : 'bg-stone-300')}
                    >
                      <span className={cn('absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full transition-transform', searchInFolder && 'translate-x-4')} />
                    </button>
                  </label>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-2 flex-shrink-0">
        {/* User menu */}
        <div ref={menuRef} className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            aria-label="用户菜单"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            className="flex items-center gap-2 px-2 py-1.5 rounded-full hover:bg-stone-100 cursor-pointer transition-all duration-200"
          >
            <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-full flex items-center justify-center ring-2 ring-transparent hover:ring-primary-200 transition-all duration-200">
              <span className="text-sm font-medium text-white">
                {user?.username?.charAt(0).toUpperCase() || 'U'}
              </span>
            </div>
          </button>

          {menuOpen && (
            <div className="absolute right-0 top-12 w-56 bg-white rounded-xl border border-stone-100 py-1.5 animate-scale-in z-50 shadow-float">
              <div className="px-4 py-2 border-b border-stone-100">
                <div className="flex items-center gap-2">
                  <UserIcon className="w-4 h-4 text-stone-400" aria-hidden />
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
                <LogOut className="w-4 h-4" aria-hidden />
                <span>退出登录</span>
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}