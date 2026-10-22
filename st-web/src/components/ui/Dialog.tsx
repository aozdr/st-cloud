import { type ReactNode, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useModalA11y } from './useModalA11y';

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
  useModalA11y(panelRef, onClose);

  return createPortal(
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
    </div>,
    document.body,
  );
}
