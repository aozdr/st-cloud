import {
  createContext,
  useContext,
  useState,
  useCallback,
  useRef,
  useEffect,
  type ReactNode,
} from 'react';
import { AlertTriangle } from 'lucide-react';

interface PromptOptions {
  title?: string;
  message: string;
  defaultValue?: string;
  placeholder?: string;
  confirmText?: string;
  cancelText?: string;
}

interface PromptContextValue {
  prompt: (opts: PromptOptions) => Promise<string | null>;
}

const PromptContext = createContext<PromptContextValue | null>(null);

export function usePrompt() {
  const ctx = useContext(PromptContext);
  if (!ctx) throw new Error('usePrompt must be used within PromptProvider');
  return ctx;
}

export function PromptProvider({ children }: { children: ReactNode }) {
  const [dialog, setDialog] = useState<{ opts: PromptOptions; resolve: (v: string | null) => void } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (dialog) {
      const t = setTimeout(() => inputRef.current?.focus(), 50);
      return () => clearTimeout(t);
    }
  }, [dialog]);

  const prompt = useCallback((opts: PromptOptions) => {
    return new Promise<string | null>((resolve) => {
      setDialog({ opts, resolve });
    });
  }, []);

  const handleConfirm = () => {
    const value = inputRef.current?.value ?? '';
    dialog?.resolve(value.trim() || null);
    setDialog(null);
  };

  const handleCancel = () => {
    dialog?.resolve(null);
    setDialog(null);
  };

  return (
    <PromptContext.Provider value={{ prompt }}>
      {children}
      {dialog && (
        <div
          className="modal-overlay"
          onClick={handleCancel}
          role="presentation"
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={dialog.opts.title || '输入'}
            tabIndex={-1}
            className="flex flex-col items-center bg-surface rounded-xl shadow-float px-8 pt-7 pb-6 w-[380px] max-w-[calc(100vw-2rem)] animate-dialog-pop focus:outline-none"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-12 h-12 rounded-full flex items-center justify-center mb-5 bg-amber-500/10">
              <AlertTriangle className="w-5 h-5 text-amber-500" strokeWidth={2} aria-hidden />
            </div>

            {dialog.opts.title && (
              <h3 className="text-base font-semibold text-fg mb-1.5 text-center">{dialog.opts.title}</h3>
            )}

            <p className="text-sm text-muted leading-relaxed mb-4 text-center max-w-[320px]">{dialog.opts.message}</p>

            <input
              ref={inputRef}
              type="text"
              defaultValue={dialog.opts.defaultValue || ''}
              placeholder={dialog.opts.placeholder || '请输入…'}
              aria-label={dialog.opts.title || dialog.opts.message}
              className="w-full px-3 py-2 text-sm bg-transparent border border-border rounded-md text-fg placeholder:text-muted/60 focus:outline-none focus:border-ring focus:ring-2 focus:ring-ring/40 mb-6"
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleConfirm();
                if (e.key === 'Escape') handleCancel();
              }}
            />

            <div className="flex w-full gap-3">
              <button
                onClick={handleCancel}
                className="flex-1 px-5 py-2.5 text-sm font-medium text-fg bg-surface-2 hover:bg-muted rounded-md cursor-pointer transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {dialog.opts.cancelText || '取消'}
              </button>
              <button
                onClick={handleConfirm}
                className="flex-1 px-5 py-2.5 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md cursor-pointer transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {dialog.opts.confirmText || '确定'}
              </button>
            </div>
          </div>
        </div>
      )}
    </PromptContext.Provider>
  );
}
