import { useEffect, useState } from 'react';

/**
 * 顶部加载进度条 - 替代全屏 spinner，减少页面抖动。
 * 纯视觉模拟进度（非真实百分比），类似 GitHub / YouTube 顶部细条。
 *
 * 机制：显示时宽度从 0% 动画到 80%，完成时推到 100% 后淡出消失。
 */
export default function TopProgressBar() {
  const [visible, setVisible] = useState(true);
  const [width, setWidth] = useState(0);
  const [fading, setFading] = useState(false);

  useEffect(() => {
    // 第一帧：开始进度动画到 80%
    const r1 = requestAnimationFrame(() => setWidth(80));
    return () => cancelAnimationFrame(r1);
  }, []);

  useEffect(() => {
    if (width < 100) return;
    // 宽度到 100% 后淡出
    const timer = setTimeout(() => setFading(true), 200);
    const hideTimer = setTimeout(() => setVisible(false), 500);
    return () => { clearTimeout(timer); clearTimeout(hideTimer); };
  }, [width]);

  if (!visible) return null;

  return (
    <div
      className="fixed top-0 left-0 right-0 z-[9999] pointer-events-none"
      style={{ opacity: fading ? 0 : 1, transition: 'opacity 0.3s ease-out' }}
      aria-hidden
    >
      <div
        className="h-[2px] bg-primary-600"
        style={{
          width: `${width}%`,
          transition: 'width 0.7s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
      />
    </div>
  );
}

/**
 * 持续型进度条组件 - 用于 Suspense fallback，挂载期间持续显示进度。
 * 与 TopProgressBar 不同，不会自动推进到 100%，由卸载时消失。
 */
export function SuspenseProgressBar() {
  const [width, setWidth] = useState(0);

  useEffect(() => {
    // 渐进推进到 85%，模拟加载中
    const r1 = requestAnimationFrame(() => setWidth(30));
    const timer1 = setTimeout(() => setWidth(60), 300);
    const timer2 = setTimeout(() => setWidth(85), 800);
    return () => { cancelAnimationFrame(r1); clearTimeout(timer1); clearTimeout(timer2); };
  }, []);

  return (
    <div className="fixed top-0 left-0 right-0 z-[9999] pointer-events-none" aria-hidden>
      <div
        className="h-[2px] bg-primary-600"
        style={{
          width: `${width}%`,
          transition: 'width 0.5s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
      />
    </div>
  );
}
