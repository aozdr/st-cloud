/** Electron 环境检测与 IPC 封装 */
import { syncDesktopAuth } from '../auth-session';

export function isElectron(): boolean {
  return typeof window !== 'undefined' && !!window.electronAPI;
}

export function getElectronAPI() {
  return isElectron() ? window.electronAPI : null;
}

/**
 * 初始化：如果 localStorage 中有 token，同步给 Electron 主进程
 */
export function syncAuthToElectron(): void {
  // 新桥接会接收主进程最新令牌，旧接口仍可只接收两项参数。
  void syncDesktopAuth().catch(() => { console.warn('桌面认证同步暂时失败'); });
}
