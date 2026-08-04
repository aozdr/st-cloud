import { create } from 'zustand';
import { THEMES, DEFAULT_THEME, applyThemeToDOM, getThemeByKey } from '../themes';
import type { ThemeKey, ThemePalette } from '../themes';

const STORAGE_KEY = 'theme';

interface ThemeState {
  themeKey: ThemeKey;
  palette: ThemePalette;
  setTheme: (key: ThemeKey) => void;
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

function loadAndApply(): { key: ThemeKey; palette: ThemePalette } {
  const key = loadThemeKey();
  const palette = getThemeByKey(key);
  applyThemeToDOM(palette);
  return { key, palette };
}

// 初始化时立即读取 localStorage 并应用主题，防止闪烁
const initial = loadAndApply();

export const useThemeStore = create<ThemeState>((set) => ({
  themeKey: initial.key,
  palette: initial.palette,
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
}));
