import { useState, useRef, useEffect } from 'react';
import { X, CheckCircle2, AlertCircle, Loader2, Zap, FileUp, Pause, ListChecks } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { formatSize } from '../../lib/utils';
import { useUpload } from '../../hooks/useUpload';
import type { UploadTask } from '../../types';

function formatEta(seconds: number): string {
  if (seconds <= 0 || !isFinite(seconds)) return '';
  if (seconds < 60) return Math.ceil(seconds) + 's';
  const m = Math.floor(seconds / 60);
  const s = Math.ceil(seconds % 60);
  return m + 'm ' + s + 's';
}

function UploadTaskItem({ task, onRemove }: { task: UploadTask; onRemove: () => void }) {
  const startTimeRef = useRef<number | null>(null);
  const startProgressRef = useRef(0);
  const [speed, setSpeed] = useState(0);
  const [eta, setEta] = useState(0);

  useEffect(() => {
    if (task.status === 'uploading' && task.fileSize > 0) {
      if (startTimeRef.current === null) {
        startTimeRef.current = Date.now();
        startProgressRef.current = task.progress;
      }
      const elapsed = (Date.now() - startTimeRef.current) / 1000;
      const progressDelta = task.progress - startProgressRef.current;
      if (progressDelta > 0 && elapsed > 0.5) {
        const uploadedBytes = (progressDelta / 100) * task.fileSize;
        const currentSpeed = uploadedBytes / elapsed;
        setSpeed(currentSpeed);
        const remainingBytes = ((100 - task.progress) / 100) * task.fileSize;
        setEta(currentSpeed > 0 ? remainingBytes / currentSpeed : 0);
      }
    } else if (task.status !== 'paused') {
      startTimeRef.current = null;
      setSpeed(0);
      setEta(0);
    }
  }, [task.status, task.progress, task.fileSize]);

  return (
    <div className="px-4 py-3 border-b border-stone-50 last:border-0">
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="text-sm text-stone-900 truncate flex-1">{task.fileName}</div>
        <button onClick={onRemove} aria-label="移除" className="text-stone-300 hover:text-stone-600 cursor-pointer flex-shrink-0">
          <X className="w-3.5 h-3.5" aria-hidden />
        </button>
      </div>
      <div className="flex items-center gap-2">
        <div className="flex-1 h-1.5 bg-stone-100 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ${
              task.status === 'failed' ? 'bg-red-500' :
              task.status === 'paused' ? 'bg-amber-500' :
              task.status === 'completed' || task.status === 'instant' ? 'bg-green-500' :
              'bg-primary-600'
            }`}
            style={{ width: `${task.progress}%` }}
          />
        </div>
        <div className="flex items-center gap-1 text-xs text-stone-500 flex-shrink-0 w-20 justify-end">
          {task.status === 'hashing' && <><Loader2 className="w-3 h-3 animate-spin" aria-hidden />计算中</>}
          {task.status === 'pending' && <span>等待中</span>}
          {task.status === 'uploading' && <>{task.progress}%</>}
          {task.status === 'merging' && <><Loader2 className="w-3 h-3 animate-spin" aria-hidden />合并中</>}
          {task.status === 'completed' && <><CheckCircle2 className="w-3 h-3 text-green-500" aria-hidden />完成</>}
          {task.status === 'instant' && <><Zap className="w-3 h-3 text-yellow-500" aria-hidden />秒传</>}
          {task.status === 'failed' && <><AlertCircle className="w-3 h-3 text-red-500" aria-hidden />失败</>}
          {task.status === 'paused' && <><Pause className="w-3 h-3 text-amber-500" aria-hidden />已暂停</>}
        </div>
      </div>
      <div className="text-xs text-stone-400 mt-1 flex items-center gap-1.5">
        <span>{formatSize(task.fileSize)}</span>
        {speed > 0 && task.status === 'uploading' && (
          <>
            <span className="text-stone-300">·</span>
            <span className="tabular-nums">{formatSize(speed)}/s</span>
            {eta > 0 && (
              <>
                <span className="text-stone-300">·</span>
                <span className="tabular-nums">{'\u5269\u4f59'} {formatEta(eta)}</span>
              </>
            )}
          </>
        )}
        {task.status === 'failed' && task.error && (
          <>
            <span className="text-stone-300">·</span>
            <span className="text-red-500">{task.error}</span>
          </>
        )}
      </div>
    </div>
  );
}

export default function UploadPanel() {
  const { tasks, panelOpen, setPanelOpen, removeTask, clearCompleted } = useUpload();
  const navigate = useNavigate();

  const activeCount = tasks.filter((t) => !['completed', 'instant', 'failed', 'paused'].includes(t.status)).length;
  const hasCompleted = tasks.some((t) => t.status === 'completed' || t.status === 'instant');

  if (!panelOpen) {
    return (
      <div className="fixed bottom-6 right-6 z-50">
        <button
          onClick={() => setPanelOpen(true)} aria-label="展开上传队列"
          className="relative w-12 h-12 bg-white rounded-full border border-stone-200 shadow-md flex items-center justify-center hover:scale-105 cursor-pointer transition-transform"
        >
          <FileUp className="w-5 h-5 text-stone-600" aria-hidden />
          {activeCount > 0 && (
            <span className="absolute -top-1 -right-1 min-w-[20px] h-5 px-1 bg-primary-600 text-white text-[11px] font-semibold rounded-full flex items-center justify-center">
              {activeCount}
            </span>
          )}
        </button>
      </div>
    );
  }

  return (
    <div className="fixed bottom-6 right-6 w-96 bg-white rounded-xl border border-stone-200 shadow-lg z-50 animate-slide-up overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3 border-b border-stone-100">
        <div className="flex items-center gap-2">
          <FileUp className="w-4 h-4 text-primary-600" aria-hidden />
          <span className="text-sm font-semibold text-stone-900">
            上传队列 {activeCount > 0 && `(${activeCount})`}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/transfers')}
            className="flex items-center gap-1 text-xs text-stone-500 hover:text-primary-600 cursor-pointer transition-colors"
          >
            <ListChecks className="w-3.5 h-3.5" aria-hidden />
            传输列表
          </button>
          {hasCompleted && (
            <button onClick={clearCompleted} className="text-xs text-stone-500 hover:text-stone-900 cursor-pointer transition-colors">
              清除已完成
            </button>
          )}
          <button onClick={() => setPanelOpen(false)} aria-label="关闭" className="text-stone-400 hover:text-stone-900 cursor-pointer transition-colors">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
      </div>

      <div className="max-h-80 overflow-y-auto">
        {tasks.map((task) => (
          <UploadTaskItem key={task.id} task={task} onRemove={() => removeTask(task.id)} />
        ))}
      </div>
    </div>
  );
}