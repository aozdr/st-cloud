import {
  createContext,
  useContext,
  useState,
  useCallback,
  type ReactNode,
} from 'react';
import { CheckCircle, XCircle, Info, AlertCircle, X } from 'lucide-react';

type ToastType = 'success' | 'error' | 'info' | 'warning';

interface ToastItem {
  id: number;
  /** 可选分组键：相同 key 的新 toast 会替换旧 toast，避免“进行中/完成”共用多次弹窗 */
  key?: string;
  message: string;
  type: ToastType;
}

interface ToastContextValue {
  showToast: (message: string, type?: ToastType, key?: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}

const ICONS = {
  success: CheckCircle,
  error: XCircle,
  info: Info,
  warning: AlertCircle,
};

const ACCENTS = {
  success: { border: 'border-green-500', icon: 'text-green-500' },
  error: { border: 'border-red-500', icon: 'text-red-500' },
  info: { border: 'border-primary-600', icon: 'text-primary-600' },
  warning: { border: 'border-amber-500', icon: 'text-amber-500' },
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const showToast = useCallback((message: string, type: ToastType = 'success', key?: string) => {
    const id = Date.now() + Math.random();
    setToasts((prev) => {
      const next = key ? prev.filter((t) => t.key !== key) : prev;
      return [...next, { id, key, message, type }];
    });
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3000);
  }, []);

  const removeToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed top-4 right-4 z-[100] flex flex-col gap-2 pointer-events-none" role="status" aria-live="polite">
        {toasts.map((toast) => {
          const Icon = ICONS[toast.type];
          const accent = ACCENTS[toast.type];
          return (
            <div
              key={toast.id}
              className={`flex items-center gap-3 bg-surface rounded-lg border-l-4 ${accent.border} shadow-md pointer-events-auto animate-toast-slide-down w-[280px] max-w-[calc(100vw-1.5rem)] px-4 py-3`}
            >
              <Icon className={`w-4 h-4 flex-shrink-0 ${accent.icon}`} strokeWidth={2} aria-hidden />

              <span className="text-sm font-medium text-fg flex-1">{toast.message}</span>

              <button
                onClick={() => removeToast(toast.id)}
                aria-label="关闭"
                className="text-muted hover:text-fg transition-colors flex-shrink-0 cursor-pointer"
              >
                <X className="w-4 h-4" aria-hidden />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}
