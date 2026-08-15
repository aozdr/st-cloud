/**
 * 运行时环境统一检测
 * 三端共用 st-web 代码,通过本模块判断当前运行环境并选择降级实现
 * 优先级: capacitor > electron > web
 */

export type RuntimeEnv = 'capacitor' | 'electron' | 'web';

/**
 * 获取当前运行环境
 * - capacitor: Capacitor 原生壳(WebView + 原生插件)
 * - electron:  Electron 桌面壳
 * - web:       纯浏览器
 */
export function getRuntime(): RuntimeEnv {
  if (typeof window !== 'undefined') {
    // Capacitor 注入 Capacitor object 到 window
    const cap = (window as unknown as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor;
    if (cap?.isNativePlatform?.()) return 'capacitor';
    if ((window as unknown as { electronAPI?: unknown }).electronAPI) return 'electron';
  }
  return 'web';
}

/** 是否在 Capacitor 原生壳内运行 */
export function isCapacitor(): boolean {
  return getRuntime() === 'capacitor';
}

/** 是否在 Electron 桌面壳内运行(向后兼容 electron.ts) */
export function isElectron(): boolean {
  return getRuntime() === 'electron';
}

/** 是否在纯浏览器内运行 */
export function isWeb(): boolean {
  return getRuntime() === 'web';
}

/**
 * 是否为移动端视口(与 Tailwind md 断点 768px 一致)
 * md 以下为移动布局,md 以上为桌面布局
 */
export function isMobileViewport(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia('(max-width: 767px)').matches;
}