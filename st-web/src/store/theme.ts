import { create } from 'zustand';
import { THEMES, DEFAULT_THEME, applyThemeToDOM, getThemeByKey } from '../themes';
import type { ThemeKey, ThemePalette } from '../themes';

const STORAGE_KEY = 'theme';
const MODE_KEY = 'themeMode';

export type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeState {
  themeKey: ThemeKey;
  palette: ThemePalette;
  mode: ThemeMode;
  setTheme: (key: ThemeKey) => void;
  setMode: (mode: ThemeMode) => void;
}

function loadThemeKey(): ThemeKey {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && THEMES.some((t) => t.key === saved)) {
      return saved as ThemeKey;
    }
  } catch {
    // localStorage 不可用时使用默认值
  }
  return DEFAULT_THEME;
}

function loadMode(): ThemeMode {
  try {
    const saved = localStorage.getItem(MODE_KEY) as ThemeMode | null;
    if (saved === 'light' || saved === 'dark' || saved === 'system') return saved;
  } catch {
    // ignore
  }
  // 对齐 .ulpi/design/DESIGN.md（V4）：暗色优先，默认 dark
  return 'dark';
}

/** Resolve the effective appearance (light or dark) from the mode. */
function resolveDark(mode: ThemeMode): boolean {
  if (mode === 'dark') return true;
  if (mode === 'light') return false;
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

function applyDarkToDOM(isDark: boolean) {
  const root = document.documentElement;
  root.classList.toggle('dark', isDark);
  root.style.colorScheme = isDark ? 'dark' : 'light';
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    meta.setAttribute('content', isDark ? '#0F0F11' : '#ffffff');
  }
}

let mediaQuery: MediaQueryList | null = null;
function startSystemListener() {
  if (typeof window === 'undefined' || !window.matchMedia) return;
  if (mediaQuery) mediaQuery.removeEventListener('change', mediaHandler);
  mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
  mediaQuery.addEventListener('change', mediaHandler);
}

function mediaHandler() {
  // Re-apply based on current store mode
  const state = useThemeStore.getState();
  if (state.mode === 'system') {
    applyDarkToDOM(resolveDark('system'));
  }
}

function loadAndApply(): { key: ThemeKey; palette: ThemePalette; mode: ThemeMode } {
  const key = loadThemeKey();
  const mode = loadMode();
  const palette = getThemeByKey(key);
  applyThemeToDOM(palette);
  applyDarkToDOM(resolveDark(mode));
  return { key, palette, mode };
}

// 初始化时立即读取 localStorage 并应用主题，防止闪烁
const initial = loadAndApply();
startSystemListener();

export const useThemeStore = create<ThemeState>((set) => ({
  themeKey: initial.key,
  palette: initial.palette,
  mode: initial.mode,
  setTheme: (key) => {
    const palette = getThemeByKey(key);
    applyThemeToDOM(palette);
    try {
      localStorage.setItem(STORAGE_KEY, key);
    } catch {
      // 忽略持久化失败
    }
    set({ themeKey: key, palette });
  },
  setMode: (mode) => {
    applyDarkToDOM(resolveDark(mode));
    try {
      localStorage.setItem(MODE_KEY, mode);
    } catch {
      // ignore
    }
    set({ mode });
  },
}));
