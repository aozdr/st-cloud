import { type ReactNode, useEffect, useRef } from 'react';
import { X } from 'lucide-react';

interface DialogProps {
  onClose: () => void;
  title: string;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  width?: string;
}

export function Dialog({ onClose, title, description, children, footer, width = 'max-w-md' }: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  // Focus trap: focus the panel on open, trap Tab within
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    // 记录打开前的焦点元素，关闭时还原（可访问性要求：键盘用户焦点回到触发按钮）
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const focusable = panel.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    focusable[0]?.focus();
    const onKeydown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    panel.addEventListener('keydown', onKeydown);
    return () => {
      panel.removeEventListener('keydown', onKeydown);
      previouslyFocused?.focus?.();
    };
  }, []);

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={`modal-content w-full ${width} max-h-[90vh] flex flex-col focus:outline-none`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between px-6 pt-5 pb-4 border-b border-border">
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-fg">{title}</h2>
            {description && <p className="text-sm text-muted mt-0.5">{description}</p>}
          </div>
          <button
            onClick={onClose}
            aria-label="关闭对话框"
            className="flex-shrink-0 text-muted hover:text-fg transition-colors cursor-pointer p-1 -mr-1 -mt-0.5 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>
        <div className="px-6 py-5 overflow-y-auto flex-1">
          {children}
        </div>
        {footer && (
          <div className="flex justify-end gap-3 px-6 py-4 border-t border-border bg-surface-2/50 rounded-b-2xl">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
