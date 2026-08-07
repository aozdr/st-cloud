import { useState, useEffect, useRef } from 'react';

export interface DragRect {
  startX: number;
  startY: number;
  currentX: number;
  currentY: number;
}

/**
 * 框选（drag-to-select）逻辑：鼠标在文件列表空白处按下并拖动时，
 * 通过 requestAnimationFrame 实时计算与文件项的碰撞，更新选中集合。
 */
export function useDragSelect(
  containerRef: React.RefObject<HTMLElement | null>,
  setSelectedIds: React.Dispatch<React.SetStateAction<Set<string>>>,
) {
  const [dragRect, setDragRect] = useState<DragRect | null>(null);
  const dragStartRef = useRef<{ x: number; y: number } | null>(null);
  const rafRef = useRef<number | null>(null);
  const lastMouseRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });

  useEffect(() => {
    let mounted = true;
    const computeSelection = () => {
      rafRef.current = null;
      if (!dragStartRef.current) return;
      const container = containerRef.current;
      if (!container) return;
      const containerRect = container.getBoundingClientRect();
      const start = dragStartRef.current;
      const x = Math.max(containerRect.left, Math.min(containerRect.right, lastMouseRef.current.x));
      const y = Math.max(containerRect.top, Math.min(containerRect.bottom, lastMouseRef.current.y));
      const left = Math.min(start.x, x);
      const right = Math.max(start.x, x);
      const top = Math.min(start.y, y);
      const bottom = Math.max(start.y, y);
      setDragRect({ startX: start.x, startY: start.y, currentX: x, currentY: y });

      const newSelected = new Set<string>();
      for (const item of container.querySelectorAll('[data-file-id]')) {
        const rect = item.getBoundingClientRect();
        if (rect.left < right && rect.right > left && rect.top < bottom && rect.bottom > top) {
          newSelected.add(item.getAttribute('data-file-id')!);
        }
      }
      if (mounted) setSelectedIds(newSelected);
    };

    const handleMove = (e: MouseEvent) => {
      if (!dragStartRef.current) return;
      lastMouseRef.current = { x: e.clientX, y: e.clientY };
      if (rafRef.current === null) rafRef.current = requestAnimationFrame(computeSelection);
    };

    const handleUp = () => {
      dragStartRef.current = null;
      setDragRect(null);
      document.body.style.userSelect = '';
    };

    document.addEventListener('mousemove', handleMove);
    document.addEventListener('mouseup', handleUp);
    return () => {
      mounted = false;
      document.body.style.userSelect = '';
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      document.removeEventListener('mousemove', handleMove);
      document.removeEventListener('mouseup', handleUp);
    };
  }, [containerRef, setSelectedIds]);

  const startDrag = (x: number, y: number) => {
    document.body.style.userSelect = 'none';
    dragStartRef.current = { x, y };
    setDragRect({ startX: x, startY: y, currentX: x, currentY: y });
  };

  return { dragRect, startDrag };
}