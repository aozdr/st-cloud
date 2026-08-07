import { useState, useRef, useEffect, type RefObject } from 'react';
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Search, LogOut, User as UserIcon, X, Home, Clock, Trash2 } from 'lucide-react';
import { useAuthStore } from '../../store/auth';

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
  const menuRef: RefObject<HTMLDivElement> = useRef(null);
  const searchRef: RefObject<HTMLDivElement> = useRef(null);
  const inputRef: RefObject<HTMLInputElement> = useRef(null);

  const isSearchPage = location.pathname === '/search';
  const isMac = typeof navigator !== 'undefined' && navigator.platform.toUpperCase().includes('MAC');

  useEffect(() => {
    setSearchHistory(loadHistory());
  }, []);

  useEffect(() => {
    if (isSearchPage) {
      setSearchValue(searchParams.get('keyword') || '');
    } else if (!location.pathname.startsWith('/search')) {
      setSearchValue('');
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
    setSearchHistory(saveHistory(trimmed));
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
    <header className="h-14 bg-stone-100 border-b border-stone-200 flex items-center px-5 flex-shrink-0 gap-4 shadow-sm">
      {/* Home */}
      <button
        onClick={() => navigate('/')}
        className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-stone-500 hover:text-primary-600 hover:bg-stone-200 cursor-pointer transition-colors flex-shrink-0"
        title="回到首页"
      >
        <Home className="w-4 h-4" aria-hidden />
        <span className="text-sm">首页</span>
      </button>

      {/* Search */}
      <div className="flex-1 flex justify-center">
        <div className="flex w-full max-w-2xl" ref={searchRef}>
          <div className="relative flex-1 group">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-stone-400 group-focus-within:text-primary-600 transition-colors" aria-hidden />
            <input
              ref={inputRef}
              type="text"
              value={searchValue}
              onChange={(e) => setSearchValue(e.target.value)}
              onKeyDown={handleSearch}
              onFocus={() => setShowHistory(true)}
              aria-label="搜索"
              placeholder="搜索文件名或文档内容..."
              className="w-full pl-10 pr-20 py-1.5 bg-white border border-stone-300 rounded-l-md text-sm text-stone-900 placeholder-stone-400 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 focus:z-10"
            />
            <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-1">
              {searchValue ? (
                <button
                  onClick={() => { setSearchValue(''); inputRef.current?.focus(); }}
                  className="w-5 h-5 flex items-center justify-center rounded-full bg-stone-200 hover:bg-stone-300 text-stone-500 hover:text-stone-700 transition cursor-pointer"
                  aria-label="清空搜索"
                >
                  <X className="w-3.5 h-3.5" strokeWidth={2.5} aria-hidden />
                </button>
              ) : (
                <kbd className="hidden sm:flex items-center gap-0.5 px-1.5 py-0.5 text-[10px] font-medium text-stone-400 bg-stone-100 border border-stone-200 rounded">
                  {isMac ? '⌘' : 'Ctrl'} K
                </kbd>
              )}
            </div>

            {/* Search history dropdown */}
            {showHistory && !searchValue && searchHistory.length > 0 && (
              <div className="absolute top-full left-0 right-0 mt-1 bg-white rounded-lg border border-stone-200 shadow-lg py-1.5 z-50 animate-scale-in">
                <div className="flex items-center justify-between px-3 py-1">
                  <span className="text-xs font-medium text-stone-400">最近搜索</span>
                  <button
                    onClick={clearHistory}
                    className="flex items-center gap-1 text-xs text-stone-400 hover:text-stone-600 cursor-pointer transition-colors"
                  >
                    <Trash2 className="w-3 h-3" aria-hidden />
                    <span>清除</span>
                  </button>
                </div>
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
            )}
          </div>
          <button
            onClick={() => executeSearch(searchValue)}
            className="px-4 py-1.5 bg-primary-600 text-white text-sm font-medium rounded-r-md hover:bg-primary-700 active:bg-primary-800 cursor-pointer transition-colors border border-l-0 border-primary-600 flex items-center gap-1.5 flex-shrink-0"
          >
            <Search className="w-4 h-4" aria-hidden />
            <span>搜索</span>
          </button>
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
            className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-stone-200 cursor-pointer transition-colors"
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