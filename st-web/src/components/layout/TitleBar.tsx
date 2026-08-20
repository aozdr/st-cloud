/**
 * 顶部标题栏：可拖拽移动窗口，颜色跟随设计 Token（浅色白底/深色深底）。
 * 最小化/最大化/关闭由系统 titleBarOverlay 按钮提供（颜色随主题由主进程同步），
 * 右侧预留 140px 给系统按钮，避免标题文字被遮挡。
 */
export default function TitleBar() {
  return <div className="app-titlebar" aria-hidden="true" />;
}
