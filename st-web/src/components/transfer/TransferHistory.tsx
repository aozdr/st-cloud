import { memo, useState, useEffect, useCallback } from 'react';
import { Trash2, ArrowUp, ArrowDown, CheckCircle2 } from 'lucide-react';
import { formatSize } from '../../lib/utils';
import type { TransferTask } from '../../types';

const HISTORY_KEY = 'transferHistory';
const MAX_HISTORY = 50;

export interface HistoryEntry {
  id: string;
  fileName: string;
  type: 'upload' | 'download';
  fileSize: string | number;
  completedAt: number;
}

export function loadHistory(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (raw) return JSON.parse(raw);
  } catch { /* ignore */ }
  return [];
}

/** 将已完成的任务追加到 localStorage 历史记录（去重，最新在前，截取最近 50 条） */
export function appendHistory(task: TransferTask): void {
  if (task.status !== 'completed') return;
  const history = loadHistory();
  if (history.some((h) => h.id === task.id)) return;
  const entry: HistoryEntry = {
    id: task.id,
    fileName: task.fileName,
    type: task.type,
    fileSize: task.fileSize || '0',
    completedAt: Date.now(),
  };
  const next = [entry, ...history].slice(0, MAX_HISTORY);
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(next)); } catch { /* quota */ }
}

function TransferHistoryInner() {
  const [history, setHistory] = useState<HistoryEntry[]>([]);

  useEffect(() => { setHistory(loadHistory()); }, []);

  const handleClear = useCallback(() => {
    localStorage.removeItem(HISTORY_KEY);
    setHistory([]);
  }, []);

  return (
    <div className="px-5 py-3">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-medium text-fg">传输历史（最近 {history.length} 条）</span>
        {history.length > 0 && (
          <button
            onClick={handleClear}
            className="flex items-center gap-1 text-xs text-muted hover:text-danger cursor-pointer transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" aria-hidden /> 清除历史
          </button>
        )}
      </div>
      {history.length === 0 ? (
        <p className="text-xs text-muted py-8 text-center">暂无传输历史记录</p>
      ) : (
        <div className="space-y-1.5">
          {history.map((h) => (
            <div key={h.id} className="flex items-center gap-3 px-3 py-2 bg-surface rounded-lg border border-border text-sm">
              {h.type === 'upload' ? (
                <ArrowUp className="w-4 h-4 text-primary-500 flex-shrink-0" aria-hidden />
              ) : (
                <ArrowDown className="w-4 h-4 text-emerald-500 flex-shrink-0" aria-hidden />
              )}
              <span className="min-w-0 flex-1 truncate text-fg" title={h.fileName}>{h.fileName}</span>
              <span className="text-xs text-muted flex-shrink-0 tabular-nums">{formatSize(h.fileSize)}</span>
              <span className="text-[11px] text-muted flex-shrink-0">
                {new Date(h.completedAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
              </span>
              <CheckCircle2 className="w-4 h-4 text-emerald-400 flex-shrink-0" aria-hidden />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default memo(TransferHistoryInner);
