import { useState, useEffect } from 'react';
import { isMobileViewport } from '../lib/runtime';

/**
 * 移动端视口响应式 hook
 * 监听 matchMedia md 断点变化,返回当前是否为移动端视口
 * 与 Tailwind md(768px) 断点一致: <768px 为移动端
 */
export function useMobile(): boolean {
  const [mobile, setMobile] = useState(isMobileViewport);

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mql = window.matchMedia('(max-width: 767px)');
    const handler = (e: MediaQueryListEvent) => setMobile(e.matches);
    mql.addEventListener('change', handler);
    setMobile(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return mobile;
}