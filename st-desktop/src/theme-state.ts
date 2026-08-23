/**
 * 应用当前深色主题的共享状态。
 * 主窗口通过 setTitleBarTheme 通知主进程；这里保存最近一次值，
 * 供悬浮窗/菜单窗加载完成后立即同步，避免首次打开时主题不一致。
 */
let isDark = false;

export function setAppThemeDark(value: boolean): void {
  isDark = !!value;
}

export function getAppThemeDark(): boolean {
  return isDark;
}
