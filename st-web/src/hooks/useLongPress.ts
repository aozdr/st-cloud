import { useRef, useCallback } from 'react';

interface LongPressOptions {
  /** 长按触发时长(ms),默认 500 */
  delay?: number;
  /** 触发移动阈值(px),超过则取消,默认 10 */
  moveThreshold?: number;
}

interface LongPressHandlers {
  onTouchStart: (e: React.TouchEvent) => void;
  onTouchMove: (e: React.TouchEvent) => void;
  onTouchEnd: () => void;
  onTouchCancel: () => void;
}

/**
 * 移动端长按 hook
 * touchstart 启动定时器,达到 delay 触发回调
 * touchmove 超过阈值或 touchend/touchcancel 取消,避免滚动误触
 */
export function useLongPress(onLongPress: () => void, options: LongPressOptions = {}): LongPressHandlers {
  const { delay = 500, moveThreshold = 10 } = options;
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startPosRef = useRef<{ x: number; y: number } | null>(null);
  const triggeredRef = useRef(false);

  const clear = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    startPosRef.current = null;
  }, []);

  const onTouchStart = useCallback((e: React.TouchEvent) => {
    const touch = e.touches[0];
    startPosRef.current = { x: touch.clientX, y: touch.clientY };
    triggeredRef.current = false;
    clear();
    timerRef.current = setTimeout(() => {
      triggeredRef.current = true;
      onLongPress();
    }, delay);
  }, [delay, onLongPress, clear]);

  const onTouchMove = useCallback((e: React.TouchEvent) => {
    if (!startPosRef.current) return;
    const touch = e.touches[0];
    const dx = Math.abs(touch.clientX - startPosRef.current.x);
    const dy = Math.abs(touch.clientY - startPosRef.current.y);
    // 超过移动阈值取消长按(用户在滚动列表)
    if (dx > moveThreshold || dy > moveThreshold) {
      clear();
    }
  }, [moveThreshold, clear]);

  const onTouchEnd = useCallback(() => {
    clear();
  }, [clear]);

  const onTouchCancel = useCallback(() => {
    clear();
  }, [clear]);

  return { onTouchStart, onTouchMove, onTouchEnd, onTouchCancel };
}