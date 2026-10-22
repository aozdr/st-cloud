import { useEffect, useRef, type RefObject } from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

let scrollLockCount = 0;
let previousBodyOverflow = '';

function lockBodyScroll() {
  if (scrollLockCount === 0) {
    previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  scrollLockCount += 1;
}

function unlockBodyScroll() {
  scrollLockCount = Math.max(0, scrollLockCount - 1);
  if (scrollLockCount === 0) {
    document.body.style.overflow = previousBodyOverflow;
  }
}

interface ModalA11yOptions {
  /** 弹窗是否处于打开状态；条件渲染的弹窗可省略，默认 true */
  open?: boolean;
  /** 打开时优先聚焦的元素，例如输入框或主操作按钮 */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** 是否允许 Escape 关闭，默认 true */
  closeOnEscape?: boolean;
}

/**
 * 弹窗可访问性：focus trap、Escape、body 滚动锁、打开/关闭焦点还原。
 * 组件需把返回的 panelRef 挂到 role="dialog" 的容器上。
 */
export function useModalA11y(
  panelRef: RefObject<HTMLElement | null>,
  onClose: () => void,
  options: ModalA11yOptions = {},
) {
  const { open = true, initialFocusRef, closeOnEscape = true } = options;
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const panel = panelRef.current;
    if (!panel) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;
    const getFocusable = () =>
      Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
        (el) => !el.hasAttribute('disabled') && el.getClientRects().length > 0,
      );

    lockBodyScroll();

    // 等一帧，确保条件渲染的输入框/按钮已经挂载。
    const focusTimer = window.setTimeout(() => {
      const active = document.activeElement as HTMLElement | null;
      const initial =
        initialFocusRef?.current ??
        (active && panel.contains(active) ? active : null) ??
        getFocusable()[0] ??
        panel;
      initial.focus?.();
    }, 0);

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeOnEscape) {
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab') return;

      const focusable = getFocusable();
      if (focusable.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    panel.addEventListener('keydown', handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      panel.removeEventListener('keydown', handleKeyDown);
      unlockBodyScroll();
      previouslyFocused?.focus?.();
    };
  }, [open, panelRef, initialFocusRef, closeOnEscape]);
}
