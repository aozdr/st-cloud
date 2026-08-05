import { type ReactNode, useEffect } from 'react';
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
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className={`modal-content w-full ${width} max-h-[90vh] flex flex-col`} onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between px-6 pt-5 pb-4 border-b border-stone-100">
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-stone-900">{title}</h2>
            {description && <p className="text-sm text-stone-500 mt-0.5">{description}</p>}
          </div>
          <button onClick={onClose} className="flex-shrink-0 text-stone-400 hover:text-stone-600 transition-colors cursor-pointer p-1 -mr-1 -mt-0.5">
            <X className="w-5 h-5" />
          </button>
        </div>
        <div className="px-6 py-5 overflow-y-auto flex-1">
          {children}
        </div>
        {footer && (
          <div className="flex justify-end gap-3 px-6 py-4 border-t border-stone-100 bg-stone-50/50 rounded-b-xl">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}