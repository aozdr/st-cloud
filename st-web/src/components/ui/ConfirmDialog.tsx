import {
  createContext,
  useContext,
  useState,
  useCallback,
  type ReactNode,
} from 'react';
import { AlertCircle, AlertTriangle } from 'lucide-react';

interface ConfirmOptions {
  title?: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
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

  const confirm = useCallback((opts: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      setDialog({ opts, resolve });
    });
  }, []);

  const handleConfirm = () => {
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
          className="fixed inset-0 z-[100] flex items-center justify-center bg-stone-900/40 animate-fade-in"
          onClick={handleCancel}
        >
          <div
            className="flex flex-col items-center bg-white rounded-xl shadow-lg px-8 pt-7 pb-6 min-w-[380px] max-w-[440px] animate-dialog-pop"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Icon */}
            <div className={`w-12 h-12 rounded-full flex items-center justify-center mb-5 ${danger ? 'bg-red-50' : 'bg-amber-50'}`}>
              <Icon className={`w-5 h-5 ${danger ? 'text-red-600' : 'text-amber-500'}`} strokeWidth={2} />
            </div>

            {/* Title */}
            {dialog.opts.title && (
              <h3 className="text-base font-semibold text-stone-900 mb-1.5 text-center">{dialog.opts.title}</h3>
            )}

            {/* Message */}
            <p className="text-sm text-stone-500 leading-relaxed mb-6 text-center max-w-[320px]">{dialog.opts.message}</p>

            {/* Buttons */}
            <div className="flex w-full gap-3">
              <button
                onClick={handleCancel}
                className="flex-1 px-5 py-2.5 text-sm font-medium text-stone-600 bg-stone-100 hover:bg-stone-200 rounded-md cursor-pointer transition-colors duration-150"
              >
                {dialog.opts.cancelText || '取消'}
              </button>
              <button
                onClick={handleConfirm}
                className={`flex-1 px-5 py-2.5 text-sm font-medium text-white rounded-md cursor-pointer transition-colors duration-150 ${
                  danger
                    ? 'bg-red-600 hover:bg-red-700'
                    : 'bg-primary-600 hover:bg-primary-700'
                }`}
              >
                {dialog.opts.confirmText || '确定'}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}
