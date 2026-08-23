import { memo, useState, useRef, useEffect, type RefObject } from 'react';
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Search, LogOut, User as UserIcon, X, Clock, Trash2, FolderOpen, Menu, ArrowLeft, ArrowRight, Moon, Sun } from 'lucide-react';
import { useAuthStore } from '../../store/auth';
import { useFolderFilterStore } from '../../store/folderFilter';
import { useThemeStore } from '../../store/theme';
import { isElectron } from '../../lib/electron';
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

interface TopBarProps {
  onMenuClick: () => void;
}

function TopBar({ onMenuClick }: TopBarProps) {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [menuOpen, setMenuOpen] = useState(false);
  const [searchValue, setSearchValue] = useState('');
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [searchInFolder, setSearchInFolder] = useState(false);
  const setFolderFilter = useFolderFilterStore((s) => s.setKeyword);
  const mode = useThemeStore((s) => s.mode);
  const setMode = useThemeStore((s) => s.setMode);
  // 有效深色：dark 模式或 system 跟随系统
  const isDark = mode === 'dark'
    || (mode === 'system' && typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-color-scheme: dark)').matches);
  // Windows 风格前进/后退：跟踪应用内 history 索引
  // react-router 的 idx 只统计应用内条目；offset 记录应用起点前已有的浏览器条目数
  const historyOffsetRef = useRef<number | null>(null);
  const readHistoryState = () => {
    const st = window.history.state as { idx?: number } | null;
    const idx = typeof st?.idx === 'number' ? st.idx : 0;
    const len = window.history.length;
    if (historyOffsetRef.current == null) {
      historyOffsetRef.current = Math.max(0, len - 1 - idx);
    }
    return { idx, len };
  };
  const syncHistory = () => {
    const { idx, len } = readHistoryState();
    setNavIdx(idx);
    setNavLen(len);
  };
  const [navIdx, setNavIdx] = useState(() => readHistoryState().idx);
  const [navLen, setNavLen] = useState(() => window.history.length);
  useEffect(() => {
    window.addEventListener('popstate', syncHistory);
    return () => window.removeEventListener('popstate', syncHistory);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // 应用内跳转（push/replace）后同步
  useEffect(() => {
    syncHistory();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location]);
  const historyOffset = historyOffsetRef.current ?? Math.max(0, window.history.length - 1 - navIdx);
  const canGoBack = navIdx > 0;
  const canGoForward = navIdx + historyOffset < navLen - 1;
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
  }, [isSearchPage, searchParams, location.pathname, setFolderFilter]);

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

  // Ctrl+F 快捷键聚焦搜索框（PikPak 风格搜索入口提示）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        const active = document.activeElement as HTMLElement | null;
        // 已在输入框中时不抢焦点，避免干扰用户输入
        if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) return;
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
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

  const btnBase = 'flex items-center justify-center rounded-full cursor-pointer transition-colors duration-200 flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

  return (
    <header className={cn('h-16 bg-surface border-b border-border-light flex items-center px-4 sm:px-8 flex-shrink-0 gap-2 sm:gap-4', !isElectron() && 'rounded-tl-2xl')}>
      {/* Mobile menu toggle */}
      <button
        onClick={onMenuClick}
        aria-label="打开菜单"
        className={cn(btnBase, 'w-9 h-9 text-muted hover:text-fg hover:bg-surface-2 lg:hidden')}
      >
        <Menu className="w-5 h-5" aria-hidden />
      </button>

      {/* Windows 风格前进/后退（桌面端） */}
      <div className="hidden sm:flex items-center gap-0.5 flex-shrink-0">
        <button
          onClick={() => navigate(-1)}
          disabled={!canGoBack}
          aria-label="后退"
          title="后退"
          className={cn(btnBase, 'w-9 h-9 text-muted hover:text-fg hover:bg-surface-2 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent')}
        >
          <ArrowLeft className="w-4 h-4" aria-hidden />
        </button>
        <button
          onClick={() => navigate(1)}
          disabled={!canGoForward}
          aria-label="前进"
          title="前进"
          className={cn(btnBase, 'w-9 h-9 text-muted hover:text-fg hover:bg-surface-2 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent')}
        >
          <ArrowRight className="w-4 h-4" aria-hidden />
        </button>
      </div>

      {/* Search (desktop/tablet) */}
      <div className="flex-1 hidden sm:flex justify-center">
        <div className="relative w-full max-w-[360px] group" ref={searchRef}>
          <div className="flex items-center h-10 bg-surface-2 border border-transparent rounded-[10px] transition-colors duration-150 group-focus-within:bg-surface group-focus-within:border-[#C9D2FF] group-focus-within:shadow-[0_0_0_3px_rgba(79,110,247,0.10)]">
            <div className="relative flex-1">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary-500 transition-colors" aria-hidden />
              <input
                ref={inputRef}
                type="text"
                value={searchValue}
                onChange={(e) => { setSearchValue(e.target.value); if (searchInFolder && isFilesPage) setFolderFilter(e.target.value); }}
                onKeyDown={handleSearch}
                onFocus={() => setShowHistory(true)}
                aria-label="搜索文件"
                placeholder="搜索文件名或文档内容… Ctrl+F"
                className="w-full pl-11 pr-3 bg-transparent border-0 text-sm text-fg placeholder:text-tertiary outline-none rounded-[10px]"
              />
            </div>
            <div className="flex items-center pr-1">
              {searchValue ? (
                <button
                  onClick={() => { setSearchValue(''); inputRef.current?.focus(); }}
                  className="w-6 h-6 flex items-center justify-center rounded-full text-muted hover:text-fg hover:bg-muted/40 transition-colors duration-150 cursor-pointer mr-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label="清空搜索"
                >
                  <X className="w-3.5 h-3.5" strokeWidth={2.5} aria-hidden />
                </button>
              ) : (
                <kbd className="hidden md:flex items-center gap-0.5 px-1.5 py-0.5 mr-2 text-[10px] font-medium text-muted bg-surface-2 border border-border rounded">
                  {isMac ? '⌘' : 'Ctrl'}&nbsp;K
                </kbd>
              )}
              <button
                onClick={() => executeSearch(searchValue)}
                className="flex items-center gap-1.5 px-3.5 h-7 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 active:bg-primary-800 cursor-pointer transition-colors duration-150 flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg"
              >
                <Search className="w-4 h-4" aria-hidden />
                <span className="hidden md:inline">搜索</span>
              </button>
            </div>
          </div>

          {/* Unified search dropdown */}
          {showHistory && (
            <div className="absolute top-full left-0 right-0 mt-1.5 bg-surface rounded-[10px] border border-border z-50 animate-scale-in shadow-card overflow-hidden flex flex-col max-h-80">
              {!searchValue && searchHistory.length > 0 && (
                <>
                  <div className="flex items-center justify-between px-3 py-1.5 flex-shrink-0">
                    <span className="text-xs font-medium text-muted">最近搜索</span>
                    <button
                      onClick={clearHistory}
                      className="flex items-center gap-1 text-xs text-muted hover:text-fg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded px-1"
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
                        className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-muted hover:bg-surface-2 hover:text-primary-600 cursor-pointer transition-colors focus-visible:outline-none focus-visible:bg-surface-2"
                      >
                        <Clock className="w-3.5 h-3.5 text-muted flex-shrink-0" aria-hidden />
                        <span className="truncate">{kw}</span>
                      </button>
                    ))}
                  </div>
                </>
              )}
              {isFilesPage && (
                <div className="border-t border-border px-3 py-2 flex-shrink-0">
                  <div className="flex items-center justify-between gap-2">
                    <label htmlFor="search-in-folder-switch" className="flex items-center gap-2 text-sm text-fg cursor-pointer">
                      <FolderOpen className="w-4 h-4 text-muted" aria-hidden />
                      搜索当前文件夹
                    </label>
                    <button
                      id="search-in-folder-switch"
                      type="button"
                      role="switch"
                      aria-checked={searchInFolder}
                      onClick={() => {
                        const next = !searchInFolder;
                        setSearchInFolder(next);
                        if (!next) { setFolderFilter(''); setSearchValue(''); }
                      }}
                      className={cn('relative w-9 h-5 rounded-full transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', searchInFolder ? 'bg-primary-600' : 'bg-muted/50')}
                    >
                      <span className={cn('absolute top-0.5 left-0.5 w-4 h-4 bg-surface rounded-full transition-transform', searchInFolder && 'translate-x-4')} aria-hidden />
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Mobile search button */}
      <button
        onClick={() => navigate('/search')}
        aria-label="搜索"
        className={cn(btnBase, 'w-9 h-9 text-muted hover:text-fg hover:bg-surface-2 sm:hidden')}
      >
        <Search className="w-5 h-5" aria-hidden />
      </button>

      <div className="flex-1 sm:hidden" />

      {/* Actions */}
      <div className="flex items-center gap-2 flex-shrink-0">
        {/* 深色模式切换 */}
        <button
          onClick={() => setMode(isDark ? 'light' : 'dark')}
          aria-label={isDark ? '切换到浅色模式' : '切换到深色模式'}
          title={isDark ? '切换到浅色模式' : '切换到深色模式'}
          className={cn(btnBase, 'w-9 h-9 text-muted hover:text-fg hover:bg-surface-2')}
        >
          {isDark ? <Sun className="w-4 h-4" aria-hidden /> : <Moon className="w-4 h-4" aria-hidden />}
        </button>
        {/* User menu */}
        <div ref={menuRef} className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            aria-label="用户菜单"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            className="flex items-center gap-2 px-2 py-1.5 rounded-full hover:bg-surface-2 cursor-pointer transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <div className="w-9 h-9 bg-primary-600 rounded-full flex items-center justify-center">
              <span className="text-sm font-medium text-white">
                {user?.username?.charAt(0).toUpperCase() || 'U'}
              </span>
            </div>
          </button>

          {menuOpen && (
            <div className="absolute right-0 top-12 w-56 bg-surface rounded-xl border border-border py-1.5 animate-scale-in z-50 shadow-float" role="menu">
              <div className="px-4 py-2 border-b border-border">
                <div className="flex items-center gap-2">
                  <UserIcon className="w-4 h-4 text-muted" aria-hidden />
                  <span className="text-sm font-medium text-fg">{user?.username || '用户'}</span>
                </div>
                {user?.nickname && (
                  <div className="text-xs text-muted mt-1 ml-6">{user.nickname}</div>
                )}
              </div>
              <button
                onClick={handleLogout}
                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-muted hover:bg-surface-2 hover:text-fg cursor-pointer transition-colors focus-visible:outline-none focus-visible:bg-surface-2"
                role="menuitem"
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

export default memo(TopBar);
