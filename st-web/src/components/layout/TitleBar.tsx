/**
 * 顶部标题栏：蓝色条 + 可拖拽移动窗口。
 * 最小化/最大化/关闭由系统 titleBarOverlay 按钮提供（右上角白色符号），
 * 右侧预留 140px 给系统按钮，避免标题文字被遮挡。
 */
export default function TitleBar() {
  return (
    <div className="app-titlebar">
      <span className="app-titlebar-title">星云盘</span>
    </div>
  );
}
