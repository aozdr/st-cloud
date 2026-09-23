import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';

interface PendingOp {
  id: number;
  label: string;
}

interface OperationProgressValue {
  /** 执行异步任务，期间顶部居中显示「label…」，任务完成后自动消失 */
  run: <T>(label: string, task: () => Promise<T>) => Promise<T>;
  pending: PendingOp[];
}

const OperationProgressContext = createContext<OperationProgressValue | null>(null);

export function useOperationProgress() {
  const ctx = useContext(OperationProgressContext);
  if (!ctx) throw new Error('useOperationProgress must be used within OperationProgressProvider');
  return ctx;
}

/** 全局耗时操作进度：顶部居中常驻提示，直到操作完成才消失（不依赖短暂 toast） */
export function OperationProgressProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<PendingOp[]>([]);
  const idRef = useRef(0);

  const run = useCallback(async <T,>(label: string, task: () => Promise<T>): Promise<T> => {
    const id = ++idRef.current;
    setPending((prev) => [...prev, { id, label }]);
    try {
      return await task();
    } finally {
      setPending((prev) => prev.filter((p) => p.id !== id));
    }
  }, []);

  return (
    <OperationProgressContext.Provider value={{ run, pending }}>
      {children}
      {/* 顶部居中操作进度：与 toast 同区域、层级更高，操作完成即消失 */}
      <div className="fixed left-1/2 top-[calc(env(safe-area-inset-top)+3rem)] z-[110] flex -translate-x-1/2 flex-col items-center gap-2 pointer-events-none">
        {pending.map((op) => (
          <div
            key={op.id}
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 px-3 py-2 bg-surface-2 rounded-lg border border-border shadow-float text-sm text-fg"
          >
            <Loader2 className="w-4 h-4 text-primary-600 animate-spin flex-shrink-0" aria-hidden />
            <span>{op.label}…</span>
          </div>
        ))}
      </div>
    </OperationProgressContext.Provider>
  );
}
