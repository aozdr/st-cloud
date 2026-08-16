import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';

interface PendingOp {
  id: number;
  label: string;
}

interface OperationProgressValue {
  /** 执行异步任务，期间右上角常驻显示「label…」，任务完成后自动消失 */
  run: <T>(label: string, task: () => Promise<T>) => Promise<T>;
  pending: PendingOp[];
}

const OperationProgressContext = createContext<OperationProgressValue | null>(null);

export function useOperationProgress() {
  const ctx = useContext(OperationProgressContext);
  if (!ctx) throw new Error('useOperationProgress must be used within OperationProgressProvider');
  return ctx;
}

/** 全局耗时操作进度：右上角常驻提示，直到操作完成才消失（不依赖短暂 toast） */
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
      {/* 右上角操作进度：与 toast 同区域、层级更高，操作完成即消失 */}
      <div className="fixed top-4 right-4 z-[110] flex flex-col items-end gap-2 pointer-events-none">
        {pending.map((op) => (
          <div
            key={op.id}
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 px-3 py-2 bg-surface rounded-lg border border-border shadow-md text-sm text-fg"
          >
            <Loader2 className="w-4 h-4 text-primary-600 animate-spin flex-shrink-0" aria-hidden />
            <span>{op.label}…</span>
          </div>
        ))}
      </div>
    </OperationProgressContext.Provider>
  );
}
