import { useState, useRef, useEffect } from 'react';
import { X, CheckCircle2, AlertCircle, Loader2, Zap, FileUp, Pause, ListChecks } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { formatSize } from '../../lib/utils';
import { useUpload } from '../../hooks/useUpload';
import type { UploadTask } from '../../types';

/** 上传任务 icon 尺寸（w-12 h-12）与默认边距 */
const FAB_SIZE = 48;
const FAB_MARGIN = 24;
const FAB_POS_KEY = 'uploadFabPos';

/** 读取用户拖拽保存的上传 icon 位置；无记录时默认右下角 */
function loadFabPos(): { left: number; top: number } {
  try {
    const raw = localStorage.getItem(FAB_POS_KEY);
    if (raw) {
      const p = JSON.parse(raw);
      if (typeof p?.left === 'number' && typeof p?.top === 'number') {
        return { left: p.left, top: p.top };
      }
    }
  } catch {
    // localStorage 不可用时使用默认位置
  }
  return {
    left: Math.max(0, (typeof window !== 'undefined' ? window.innerWidth : 1280) - FAB_SIZE - FAB_MARGIN),
    top: Math.max(0, (typeof window !== 'undefined' ? window.innerHeight : 800) - FAB_SIZE - FAB_MARGIN),
  };
}

function formatEta(seconds: number): string {
  if (seconds <= 0 || !isFinite(seconds)) return '';
  if (seconds < 60) return Math.ceil(seconds) + 's';
  const m = Math.floor(seconds / 60);
  const s = Math.ceil(seconds % 60);
  return m + 'm ' + s + 's';
}

/** 限速模式预估剩余时间：Xh Ym，超过 24h 显示 >24h（uispec 要求） */
function formatRelayEta(seconds: number): string {
  if (seconds <= 0 || !isFinite(seconds)) return '';
  const totalMinutes = Math.ceil(seconds / 60);
  if (totalMinutes >= 24 * 60) return '>24h';
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
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
    <div className="px-4 py-3 border-b border-border last:border-0">
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="text-sm text-fg truncate flex-1">{task.fileName}</div>
        <button onClick={onRemove} aria-label="移除" className="text-muted hover:text-fg cursor-pointer flex-shrink-0">
          <X className="w-3.5 h-3.5" aria-hidden />
        </button>
      </div>
      <div className="flex items-center gap-2">
        <div className="flex-1 h-1.5 bg-surface-2 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-[width] duration-300 ${
              task.status === 'failed' ? 'bg-red-500' :
              task.status === 'paused' ? 'bg-amber-500' :
              task.status === 'completed' || task.status === 'instant' ? 'bg-green-500' :
              'bg-primary-600'
            }`}
            style={{ width: `${task.progress}%` }}
          />
        </div>
        <div className="flex items-center gap-1 text-xs text-muted flex-shrink-0 w-20 justify-end">
          {task.status === 'hashing' && <><Loader2 className="w-3 h-3 animate-spin" aria-hidden />计算中</>}
          {task.status === 'pending' && <span>等待中</span>}
          {task.status === 'uploading' && task.transferMode === 'relay' && (
            <span className="text-amber-600 dark:text-amber-400 flex items-center gap-1">
              <Loader2 className="w-3 h-3 animate-spin" aria-hidden />限速中转上传中
            </span>
          )}
          {task.status === 'uploading' && task.transferMode !== 'relay' && <>{task.progress}%</>}
          {task.status === 'merging' && <><Loader2 className="w-3 h-3 animate-spin" aria-hidden />合并中</>}
          {task.status === 'completed' && <><CheckCircle2 className="w-3 h-3 text-green-500" aria-hidden />完成</>}
          {task.status === 'instant' && <><Zap className="w-3 h-3 text-yellow-500" aria-hidden />秒传</>}
          {task.status === 'failed' && <><AlertCircle className="w-3 h-3 text-red-500" aria-hidden />失败</>}
          {task.status === 'paused' && <><Pause className="w-3 h-3 text-amber-500" aria-hidden />已暂停</>}
        </div>
      </div>
      <div className="text-xs text-muted mt-1 flex items-center gap-1.5">
        <span>{formatSize(task.fileSize)}</span>
        {task.status === 'uploading' && task.transferMode === 'relay' && (task.relayLimitKb || 0) > 0 && (
          <>
            <span className="px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600 dark:text-amber-400 text-[11px] font-medium">
              限速 {task.relayLimitKb} KB/s
            </span>
            <span className="text-muted">·</span>
            <span className="tabular-nums">
              {formatRelayEta((((100 - task.progress) / 100) * task.fileSize) / ((task.relayLimitKb || 0) * 1024))}
            </span>
          </>
        )}
        {speed > 0 && task.status === 'uploading' && task.transferMode !== 'relay' && (
          <>
            <span className="text-muted">·</span>
            <span className="tabular-nums">{formatSize(speed)}/s</span>
            {eta > 0 && (
              <>
                <span className="text-muted">·</span>
                <span className="tabular-nums">{'\u5269\u4f59'} {formatEta(eta)}</span>
              </>
            )}
          </>
        )}
        {task.status === 'failed' && task.error && (
          <>
            <span className="text-muted">·</span>
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
  // 上传任务 icon 可拖拽移动；展开的队列面板仍固定在右下角原位置
  const [fabPos, setFabPos] = useState(loadFabPos);
  const fabPosRef = useRef(fabPos);
  fabPosRef.current = fabPos;
  const fabDragRef = useRef<{ startX: number; startY: number; origLeft: number; origTop: number; moved: boolean } | null>(null);
  const fabJustDraggedRef = useRef(false);

  const handleFabPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    fabDragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      origLeft: fabPos.left,
      origTop: fabPos.top,
      moved: false,
    };
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const handleFabPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    const d = fabDragRef.current;
    if (!d) return;
    const dx = e.clientX - d.startX;
    const dy = e.clientY - d.startY;
    if (!d.moved && Math.hypot(dx, dy) > 4) d.moved = true;
    if (!d.moved) return;
    const maxLeft = Math.max(0, window.innerWidth - FAB_SIZE);
    const maxTop = Math.max(0, window.innerHeight - FAB_SIZE);
    setFabPos({
      left: Math.min(Math.max(0, d.origLeft + dx), maxLeft),
      top: Math.min(Math.max(0, d.origTop + dy), maxTop),
    });
  };

  const handleFabPointerUp = (e: React.PointerEvent<HTMLButtonElement>) => {
    const d = fabDragRef.current;
    fabDragRef.current = null;
    try {
      e.currentTarget.releasePointerCapture(e.pointerId);
    } catch {
      // 指针捕获已释放时忽略
    }
    if (d?.moved) {
      // 拖拽结束：持久化位置；阻止随后的 click 展开面板
      fabJustDraggedRef.current = true;
      try {
        localStorage.setItem(FAB_POS_KEY, JSON.stringify(fabPosRef.current));
      } catch {
        // 忽略持久化失败
      }
    }
  };

  const handleFabClick = () => {
    if (fabJustDraggedRef.current) {
      fabJustDraggedRef.current = false;
      return;
    }
    setPanelOpen(true);
  };

  const activeCount = tasks.filter((t) => !['completed', 'instant', 'failed', 'paused'].includes(t.status)).length;
  const hasCompleted = tasks.some((t) => t.status === 'completed' || t.status === 'instant');

  if (!panelOpen) {
    return (
      <div className="fixed z-50" style={{ left: fabPos.left, top: fabPos.top }}>
        <button
          onClick={handleFabClick}
          onPointerDown={handleFabPointerDown}
          onPointerMove={handleFabPointerMove}
          onPointerUp={handleFabPointerUp}
          aria-label="展开上传队列"
          title="拖拽可移动，点击展开上传队列"
          className="relative w-12 h-12 bg-surface rounded-full border border-border shadow-card flex items-center justify-center hover:bg-surface-2 cursor-grab active:cursor-grabbing transition-colors touch-none select-none"
        >
          <FileUp className="w-5 h-5 text-muted" aria-hidden />
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
    <div className="fixed bottom-6 right-6 w-96 bg-surface rounded-xl border border-border shadow-lg z-50 animate-slide-up overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3 border-b border-border">
        <div className="flex items-center gap-2">
          <FileUp className="w-4 h-4 text-primary-600" aria-hidden />
          <span className="text-sm font-semibold text-fg">
            上传队列 {activeCount > 0 && `(${activeCount})`}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/transfers')}
            className="flex items-center gap-1 text-xs text-muted hover:text-primary-600 cursor-pointer transition-colors"
          >
            <ListChecks className="w-3.5 h-3.5" aria-hidden />
            传输列表
          </button>
          {hasCompleted && (
            <button onClick={clearCompleted} className="text-xs text-muted hover:text-fg cursor-pointer transition-colors">
              清除已完成
            </button>
          )}
          <button onClick={() => setPanelOpen(false)} aria-label="关闭" className="text-muted hover:text-fg cursor-pointer transition-colors">
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
