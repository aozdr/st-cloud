import {
  createContext,
  useContext,
  useState,
  useCallback,
  useRef,
  useEffect,
  type ReactNode,
} from 'react';
import { AlertCircle, AlertTriangle, Loader2 } from 'lucide-react';

interface ConfirmOptions {
  title?: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
  /** 确认后执行的异步操作：执行期间确认按钮显示加载态；成功 resolve(true)，失败 resolve(false) 并关闭 */
  onConfirm?: () => Promise<void>;
}

interface ConfirmContextValue {
  confirm: (opts: ConfirmOptions) => Promise<boolean>;
}

const ConfirmContext = createContext<ConfirmContextValue | null>(null);

export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used within ConfirmProvider');
  return ctx;
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [dialog, setDialog] = useState<{ opts: ConfirmOptions; resolve: (v: boolean) => void } | null>(null);
  const [pending, setPending] = useState(false);
  const confirmBtnRef = useRef<HTMLButtonElement>(null);

  const confirm = useCallback((opts: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      setDialog({ opts, resolve });
    });
  }, []);

  useEffect(() => {
    if (dialog) {
      const t = setTimeout(() => confirmBtnRef.current?.focus(), 50);
      return () => clearTimeout(t);
    }
  }, [dialog]);

  const handleConfirm = async () => {
    if (!dialog || pending) return;
    if (dialog.opts.onConfirm) {
      setPending(true);
      try {
        await dialog.opts.onConfirm();
        setPending(false);
        dialog.resolve(true);
        setDialog(null);
      } catch {
        setPending(false);
        dialog.resolve(false);
        setDialog(null);
      }
      return;
    }
    dialog?.resolve(true);
    setDialog(null);
  };

  const handleCancel = () => {
    dialog?.resolve(false);
    setDialog(null);
  };

  const danger = dialog?.opts.danger ?? false;
  const Icon = danger ? AlertCircle : AlertTriangle;

  return (
    <ConfirmContext.Provider value={{ confirm }}>
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
            aria-label={dialog.opts.title || '确认操作'}
            tabIndex={-1}
            className="flex flex-col items-center bg-surface rounded-xl shadow-float px-8 pt-7 pb-6 w-[380px] max-w-[calc(100vw-2rem)] animate-dialog-pop focus:outline-none"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Icon */}
            <div className={`w-12 h-12 rounded-full flex items-center justify-center mb-5 ${danger ? 'bg-red-500/10' : 'bg-amber-500/10'}`}>
              <Icon className={`w-5 h-5 ${danger ? 'text-red-500' : 'text-amber-500'}`} strokeWidth={2} aria-hidden />
            </div>

            {/* Title */}
            {dialog.opts.title && (
              <h3 className="text-base font-semibold text-fg mb-1.5 text-center">{dialog.opts.title}</h3>
            )}

            {/* Message */}
            <p className="text-sm text-muted leading-relaxed mb-6 text-center max-w-[320px]">{dialog.opts.message}</p>

            {/* Buttons */}
            <div className="flex w-full gap-3">
              <button
                onClick={handleCancel}
                className="flex-1 px-5 py-2.5 text-sm font-medium text-fg bg-surface-2 hover:bg-muted rounded-md cursor-pointer transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {dialog.opts.cancelText || '取消'}
              </button>
              <button
                ref={confirmBtnRef}
                onClick={handleConfirm}
                disabled={pending}
                className={`flex-1 px-5 py-2.5 text-sm font-medium text-white rounded-md cursor-pointer transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60 disabled:cursor-not-allowed ${
                  danger
                    ? 'bg-red-600 hover:bg-red-700'
                    : 'bg-primary-600 hover:bg-primary-700'
                }`}
              >
                {pending ? (
                  <span className="flex items-center justify-center gap-2">
                    <Loader2 className="w-4 h-4 animate-spin" aria-hidden />
                    处理中…
                  </span>
                ) : (
                  dialog.opts.confirmText || '确定'
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}
