import { useRef, useCallback, useState } from 'react';

interface PullToRefreshOptions {
  /** 触发刷新的下拉距离(px),默认 70 */
  threshold?: number;
  /** 阻尼系数,下拉距离 = 实际移动 * 阻尼,默认 0.5 */
  damping?: number;
  onRefresh: () => void | Promise<void>;
}

interface PullToRefreshResult {
  pulling: boolean;
  pullDistance: number;
  refreshing: boolean;
  onTouchStart: (e: React.TouchEvent) => void;
  onTouchMove: (e: React.TouchEvent) => void;
  onTouchEnd: () => void;
}

/**
 * 移动端下拉刷新 hook
 * 仅当滚动容器在顶部(scrollTop<=0)时下拉才触发
 * 达到阈值松手触发 onRefresh,未达阈值回弹
 */
export function usePullToRefresh({
  threshold = 70,
  damping = 0.5,
  onRefresh,
}: PullToRefreshOptions): PullToRefreshResult {
  const [pulling, setPulling] = useState(false);
  const [pullDistance, setPullDistance] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const startYRef = useRef(0);
  const activeRef = useRef(false);

  const onTouchStart = useCallback((e: React.TouchEvent) => {
    if (refreshing) return;
    const el = e.currentTarget as HTMLElement;
    // 仅在滚动容器顶部时启用下拉
    if (el.scrollTop <= 0) {
      startYRef.current = e.touches[0].clientY;
      activeRef.current = true;
    } else {
      activeRef.current = false;
    }
  }, [refreshing]);

  const onTouchMove = useCallback((e: React.TouchEvent) => {
    if (!activeRef.current || refreshing) return;
    const delta = e.touches[0].clientY - startYRef.current;
    if (delta <= 0) {
      setPulling(false);
      setPullDistance(0);
      return;
    }
    // 阻尼:越往下拉阻力越大
    const distance = Math.min(delta * damping, threshold * 1.5);
    setPulling(true);
    setPullDistance(distance);
  }, [damping, threshold, refreshing]);

  const onTouchEnd = useCallback(async () => {
    if (!activeRef.current) return;
    activeRef.current = false;
    if (pullDistance >= threshold) {
      setRefreshing(true);
      setPullDistance(threshold);
      try {
        await onRefresh();
      } finally {
        setRefreshing(false);
        setPullDistance(0);
        setPulling(false);
      }
    } else {
      setPullDistance(0);
      setPulling(false);
    }
  }, [pullDistance, threshold, onRefresh]);

  return { pulling, pullDistance, refreshing, onTouchStart, onTouchMove, onTouchEnd };
}