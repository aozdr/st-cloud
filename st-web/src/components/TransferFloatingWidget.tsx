import { useState } from 'react';
import { ChevronUp, ChevronDown, Upload, CheckCircle2, X, Loader2 } from 'lucide-react';
import { useUpload } from '../hooks/useUpload';
import { cn } from '../lib/utils';

/**
 * 传输浮窗：全局悬浮显示 Web 端上传进度
 * 有活跃上传任务时在右下角显示，可展开/收起
 */
export default function TransferFloatingWidget() {
  const { tasks, removeTask, clearCompleted } = useUpload();
  const [expanded, setExpanded] = useState(false);

  // 仅在有任务时显示
  if (tasks.length === 0) return null;

  const activeTasks = tasks.filter((t) => t.status === 'uploading' || t.status === 'pending' || t.status === 'hashing');
  const completedTasks = tasks.filter((t) => t.status === 'completed');

  const totalProgress = tasks.length > 0
    ? tasks.reduce((sum, t) => sum + (t.progress || 0), 0) / tasks.length
    : 0;

  return (
    <div className="fixed bottom-4 right-4 md:bottom-4 bottom-20 z-40 w-72 max-w-[calc(100vw-1.5rem)] bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in">
      {/* 头部：点击展开/收起 */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-2 px-3 py-2.5 hover:bg-surface-2 transition-colors cursor-pointer"
      >
        {activeTasks.length > 0 ? (
          <Loader2 className="w-4 h-4 text-primary-600 animate-spin flex-shrink-0" />
        ) : (
          <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0" />
        )}
        <span className="text-sm font-medium text-fg flex-1 text-left">
          {activeTasks.length > 0 ? `上传中 ${activeTasks.length}/${tasks.length}` : `已完成 ${completedTasks.length}`}
        </span>
        {/* 进度条 */}
        <div className="w-16 h-1.5 bg-surface-3 rounded-full overflow-hidden">
          <div
            className="h-full bg-primary-600 rounded-full transition-[width] duration-300"
            style={{ width: `${totalProgress}%` }}
          />
        </div>
        {expanded ? (
          <ChevronDown className="w-4 h-4 text-muted flex-shrink-0" />
        ) : (
          <ChevronUp className="w-4 h-4 text-muted flex-shrink-0" />
        )}
      </button>

      {/* 展开内容：任务列表 */}
      {expanded && (
        <div className="max-h-64 overflow-auto border-t border-border">
          {tasks.slice(0, 20).map((task) => (
            <div key={task.id} className="flex items-center gap-2 px-3 py-2 hover:bg-surface-2 group">
              <Upload className={cn('w-3.5 h-3.5 flex-shrink-0', task.status === 'failed' ? 'text-red-500' : task.status === 'completed' ? 'text-emerald-500' : 'text-primary-500')} />
              <div className="flex-1 min-w-0">
                <div className="text-xs font-medium text-fg truncate">{task.fileName}</div>
                <div className="flex items-center gap-1.5 mt-0.5">
                  {task.status === 'uploading' && (
                    <div className="flex-1 h-1 bg-surface-3 rounded-full overflow-hidden">
                      <div className="h-full bg-primary-500 rounded-full transition-[width] duration-300" style={{ width: `${task.progress}%` }} />
                    </div>
                  )}
                  <span className={cn(
                    'text-[10px] flex-shrink-0',
                    task.status === 'failed' ? 'text-red-500' : task.status === 'completed' ? 'text-emerald-500' : 'text-muted'
                  )}>
                    {task.status === 'failed' ? '失败' : task.status === 'completed' ? '完成' : task.status === 'hashing' ? '计算中' : `${Math.round(task.progress || 0)}%`}
                  </span>
                </div>
              </div>
              {(task.status === 'completed' || task.status === 'failed') && (
                <button
                  onClick={() => removeTask(task.id)}
                  className="w-5 h-5 flex items-center justify-center text-muted hover:text-fg opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0"
                  aria-label="移除"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          ))}
          {tasks.length > 20 && (
            <div className="text-center text-[10px] text-muted py-1.5">还有 {tasks.length - 20} 个任务…</div>
          )}
          {completedTasks.length > 0 && (
            <button
              onClick={clearCompleted}
              className="w-full py-2 text-xs text-primary-600 hover:bg-primary-500/5 border-t border-border cursor-pointer"
            >
              清除已完成
            </button>
          )}
        </div>
      )}
    </div>
  );
}
