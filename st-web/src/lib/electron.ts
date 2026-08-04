/**
 * Electron 环境检测与 IPC 封装
 */

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
  if (!isElectron()) return;
  const token = localStorage.getItem('accessToken');
  const refreshToken = localStorage.getItem('refreshToken');
  if (token && refreshToken) {
    window.electronAPI!.setAuth(token, refreshToken);
  }
}
