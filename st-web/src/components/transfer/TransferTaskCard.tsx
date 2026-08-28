import { memo } from 'react';
import {
  ArrowUp, ArrowDown, Pause, Play, X, CheckCircle2, AlertCircle,
  Loader2, FileText, FolderOpen, Clock, Trash2,
} from 'lucide-react';
import { formatSize, getFileTypeConfig } from '../../lib/utils';
import FileTypeIcon from '../file/FileTypeIcon';
import type { TransferTask, TransferStatus } from '../../types';

function formatSpeed(speed: number): string {
  if (speed <= 0) return '0 B/s';
  return `${formatSize(speed)}/s`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

const statusConfig: Record<TransferStatus, { label: string; color: string; icon: typeof CheckCircle2 }> = {
  pending: { label: '等待中', color: 'text-muted', icon: Clock },
  hashing: { label: '计算中', color: 'text-blue-500', icon: Loader2 },
  uploading: { label: '上传中', color: 'text-blue-500', icon: ArrowUp },
  downloading: { label: '下载中', color: 'text-emerald-500', icon: ArrowDown },
  paused: { label: '已暂停', color: 'text-amber-500', icon: Pause },
  merging: { label: '合并中', color: 'text-purple-500', icon: Loader2 },
  completed: { label: '已完成', color: 'text-emerald-500', icon: CheckCircle2 },
  failed: { label: '失败', color: 'text-red-500', icon: AlertCircle },
};

const activeStatuses = ['uploading', 'downloading', 'hashing', 'merging', 'pending'];

interface TransferTaskCardProps {
  task: TransferTask;
  onPause: (taskId: string, type: 'upload' | 'download') => void;
  onResume: (taskId: string, type: 'upload' | 'download') => void;
  onCancel: (taskId: string, type: 'upload' | 'download') => void;
  onOpenFile: (savePath: string) => void;
  onShowInFolder: (savePath: string) => void;
  onDelete: (task: TransferTask) => void;
}

/**
 * 单条传输任务卡片（视觉升级版）：
 * - 左侧 3px 类型色条（上传蓝 / 下载绿），扫一眼即可分辨方向
 * - 活跃进度条用品牌渐变 + 光泽流动动画
 * - 悬浮抬升 + 阴影加深，强化可交互感
 */
function TransferTaskCard({ task, onPause, onResume, onCancel, onOpenFile, onShowInFolder, onDelete }: TransferTaskCardProps) {
  const cfg = statusConfig[task.status];
  const StatusIcon = cfg.icon;
  const isActive = activeStatuses.includes(task.status);
  const isPaused = task.status === 'paused';
  const isUpload = task.type === 'upload';
  // 从文件名提取扩展名，渲染与文件列表一致的类型图标（所有状态的任务都显示）
  const dotIdx = task.fileName.lastIndexOf('.');
  const suffix = dotIdx > 0 ? task.fileName.slice(dotIdx + 1) : null;
  const fileCfg = getFileTypeConfig(1, suffix);
  const accentText = isUpload ? 'text-blue-600 dark:text-blue-400' : 'text-emerald-600 dark:text-emerald-400';
  const stripe = isUpload ? 'bg-gradient-to-b from-blue-500 to-blue-400' : 'bg-gradient-to-b from-emerald-500 to-emerald-400';
  const barFill = isUpload
    ? 'bg-gradient-to-r from-blue-600 to-sky-400'
    : 'bg-gradient-to-r from-emerald-600 to-teal-400';

  return (
    <div className="group relative bg-surface rounded-xl border border-border mb-2.5 overflow-hidden transition-all duration-200 hover:shadow-card hover:-translate-y-0.5 hover:border-primary-200/60 focus-within:ring-2 focus-within:ring-ring/40">
      {/* 左侧类型色条 */}
      <div className={`absolute left-0 top-0 bottom-0 w-[3px] ${stripe}`} aria-hidden />

      <div className="flex items-start gap-3 pl-4 pr-3 py-3">
        {/* 文件类型图标：直接展示彩色类型图标，不套底色容器 */}
        <div className="flex-shrink-0 mt-0.5 w-6 flex justify-center">
          <FileTypeIcon config={fileCfg} size="md" suffix={suffix} />
        </div>

        {/* 主体信息 */}
        <div className="flex-1 min-w-0">
          {/* 文件名 + 状态徽标行 */}
          <div className="flex items-center gap-2 mb-1">
            <p className="text-sm font-medium text-fg truncate flex-1 min-w-0" title={task.fileName}>
              {task.fileName}
            </p>
            {isActive && (
              <span className={`text-[11px] font-medium flex items-center gap-1 flex-shrink-0 ${accentText}`}>
                <StatusIcon className="w-3 h-3 animate-pulse" aria-hidden />
                {cfg.label}
              </span>
            )}
            {isPaused && (
              <span className="px-1.5 py-0.5 text-[11px] font-medium text-amber-600 dark:text-amber-400 bg-amber-500/15 rounded-full flex-shrink-0 flex items-center gap-1">
                <Pause className="w-2.5 h-2.5" aria-hidden />
                已暂停
              </span>
            )}
            {task.status === 'completed' && (
              <span className="px-1.5 py-0.5 text-[11px] font-medium text-emerald-600 dark:text-emerald-400 bg-emerald-500/15 rounded-full flex-shrink-0 flex items-center gap-1">
                <CheckCircle2 className="w-2.5 h-2.5" aria-hidden />
                已完成
              </span>
            )}
            {task.status === 'failed' && (
              <span className="px-1.5 py-0.5 text-[11px] font-medium text-red-600 dark:text-red-400 bg-red-500/15 rounded-full flex-shrink-0 flex items-center gap-1">
                <AlertCircle className="w-2.5 h-2.5" aria-hidden />
                失败
              </span>
            )}
          </div>

          {/* 进度条（活跃/暂停） */}
          {(isActive || isPaused) && (
            <div className="flex items-center gap-2.5 mb-1.5">
              <div className="flex-1 h-2 bg-surface-2 rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full transition-[width] duration-500 ease-out ${
                    isPaused ? 'bg-amber-400' : `${barFill} ${isPaused ? '' : 'progress-sheen'}`
                  }`}
                  style={{ width: `${Math.max(task.progress, 2)}%` }}
                />
              </div>
              <span className="text-xs font-bold text-fg tabular-nums w-10 text-right">{task.progress}%</span>
            </div>
          )}

          {/* meta 信息行 */}
          <div className="flex items-center gap-1.5 text-xs text-muted">
            {(isActive || isPaused) ? (
              <>
                <span className="tabular-nums font-medium">{formatSize(task.transferredBytes)}</span>
                <span className="text-border">/</span>
                <span className="tabular-nums">{formatSize(task.fileSize)}</span>
              </>
            ) : (
              <>
                <span className="tabular-nums">{formatSize(task.fileSize)}</span>
                <span className="text-border">·</span>
                <span className="tabular-nums">{formatTime(task.createdAt)}</span>
              </>
            )}
            {/* 中转模式限速提示 */}
            {task.type === 'upload' && task.transferMode === 'relay' && isActive && (
              <>
                <span className="text-border">·</span>
                <span className="text-amber-600 dark:text-amber-400 flex items-center gap-0.5">
                  <Loader2 className="w-3 h-3 animate-spin" aria-hidden />
                  限速中转 {task.relayLimitKb || '?'} KB/s
                </span>
              </>
            )}
          </div>

          {/* 错误信息 */}
          {task.error && (
            <div className="mt-1.5 text-xs text-red-500 dark:text-red-400 truncate flex items-center gap-1">
              <AlertCircle className="w-3 h-3 flex-shrink-0" aria-hidden />
              {task.error}
            </div>
          )}
        </div>

        {/* 右侧：速度徽章 + 操作按钮 */}
        <div className="flex flex-col items-end justify-between gap-1.5 flex-shrink-0">
          {isActive && (
            <div className={`px-2 py-0.5 rounded-md text-xs font-bold tabular-nums text-white shadow-sm flex items-center gap-1 ${
              isUpload
                ? 'bg-gradient-to-r from-blue-500 to-sky-500 shadow-blue-500/20'
                : 'bg-gradient-to-r from-emerald-500 to-teal-500 shadow-emerald-500/20'
            }`}>
              {isUpload ? <ArrowUp className="w-3 h-3" aria-hidden /> : <ArrowDown className="w-3 h-3" aria-hidden />}
              {formatSpeed(task.speed)}
            </div>
          )}

          <div className="flex items-center gap-0.5 opacity-60 group-hover:opacity-100 transition-opacity">
            {task.status === 'completed' && task.type === 'download' && task.savePath && (
              <>
                <button
                  onClick={() => onOpenFile(task.savePath!)}
                  className="p-1.5 text-muted hover:text-primary-600 hover:bg-primary-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="打开文件" aria-label="打开文件"
                >
                  <FileText className="w-4 h-4" aria-hidden />
                </button>
                <button
                  onClick={() => onShowInFolder(task.savePath!)}
                  className="p-1.5 text-muted hover:text-primary-600 hover:bg-primary-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="打开所在文件夹" aria-label="打开所在文件夹"
                >
                  <FolderOpen className="w-4 h-4" aria-hidden />
                </button>
              </>
            )}
            {(isActive || isPaused) && (
              <>
                {isPaused ? (
                  <button
                    onClick={() => onResume(task.id, task.type)}
                    className="p-1.5 text-muted hover:text-emerald-600 dark:hover:text-emerald-400 hover:bg-emerald-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    title="继续" aria-label="继续"
                  >
                    <Play className="w-4 h-4" aria-hidden />
                  </button>
                ) : (
                  <button
                    onClick={() => onPause(task.id, task.type)}
                    className="p-1.5 text-muted hover:text-amber-600 dark:hover:text-amber-400 hover:bg-amber-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    title="暂停" aria-label="暂停"
                  >
                    <Pause className="w-4 h-4" aria-hidden />
                  </button>
                )}
                <button
                  onClick={() => onCancel(task.id, task.type)}
                  className="p-1.5 text-muted hover:text-red-600 dark:hover:text-red-400 hover:bg-red-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="取消" aria-label="取消"
                >
                  <X className="w-4 h-4" aria-hidden />
                </button>
              </>
            )}
            {!isActive && !isPaused && (
              <button
                onClick={() => onDelete(task)}
                className="p-1.5 text-muted hover:text-red-600 dark:hover:text-red-400 hover:bg-red-500/10 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                title="删除" aria-label="删除"
              >
                <Trash2 className="w-4 h-4" aria-hidden />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/** 任务级组件：task 与回调引用稳定时跳过重渲染，长列表滚动性能优化 */
export default memo(TransferTaskCard);
