import { useState, useEffect, useCallback } from 'react';
import {
  ArrowUp, ArrowDown, Pause, Play, X, CheckCircle2, AlertCircle,
  Loader2, FileUp, Clock, FolderOpen, FileText, Trash2,
  RefreshCw, Settings2
} from 'lucide-react';
import { isElectron } from '../lib/electron';
import { formatSize } from '../lib/utils';
import type { TransferTask, TransferStatus } from '../types';
import TransferSettingsDialog from '../components/TransferSettingsDialog';

function formatSpeed(speed: number): string {
  if (speed <= 0) return '0 B/s';
  return `${formatSize(speed)}/s`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

const statusConfig: Record<TransferStatus, { label: string; color: string; icon: typeof CheckCircle2 }> = {
  pending: { label: '等待中', color: 'text-stone-400', icon: Clock },
  hashing: { label: '计算中', color: 'text-blue-500', icon: Loader2 },
  uploading: { label: '上传中', color: 'text-blue-500', icon: ArrowUp },
  downloading: { label: '下载中', color: 'text-emerald-500', icon: ArrowDown },
  paused: { label: '已暂停', color: 'text-amber-500', icon: Pause },
  merging: { label: '合并中', color: 'text-purple-500', icon: Loader2 },
  completed: { label: '已完成', color: 'text-emerald-500', icon: CheckCircle2 },
  failed: { label: '失败', color: 'text-red-500', icon: AlertCircle },
};

const activeStatuses = ['uploading', 'downloading', 'hashing', 'merging', 'pending'];

export default function TransferManager() {
  const [tasks, setTasks] = useState<TransferTask[]>([]);
  const [filter, setFilter] = useState<'all' | 'upload' | 'download' | 'active'>('all');
  const [deleteTarget, setDeleteTarget] = useState<TransferTask | null>(null);
  const [deleteSourceFile, setDeleteSourceFile] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [transferSettingsOpen, setTransferSettingsOpen] = useState(false);

  // 初始加载
  useEffect(() => {
    if (!isElectron()) return;
    window.electronAPI!.getTasks().then(setTasks);
  }, []);

  // 监听任务更新
  useEffect(() => {
    if (!isElectron()) return;
    const unsubscribe = window.electronAPI!.onTaskUpdate((updatedTask: TransferTask) => {
      setTasks((prev) => {
        const idx = prev.findIndex((t) => t.id === updatedTask.id);
        if (idx === -1) return [updatedTask, ...prev];
        const next = [...prev];
        next[idx] = updatedTask;
        return next;
      });
    });
    return unsubscribe;
  }, []);

  const refresh = useCallback(async () => {
    if (!isElectron()) return;
    const all = await window.electronAPI!.getTasks();
    setTasks(all);
  }, []);

  const handlePause = useCallback(async (taskId: string, type: 'upload' | 'download') => {
    if (!isElectron()) return;
    if (type === 'upload') {
      await window.electronAPI!.pauseUpload(taskId);
    } else {
      await window.electronAPI!.pauseDownload(taskId);
    }
  }, []);

  const handleResume = useCallback(async (taskId: string, type: 'upload' | 'download') => {
    if (!isElectron()) return;
    if (type === 'upload') {
      await window.electronAPI!.resumeUpload(taskId);
    } else {
      await window.electronAPI!.resumeDownload(taskId);
    }
  }, []);

  const handleCancel = useCallback(async (taskId: string, type: 'upload' | 'download') => {
    if (!isElectron()) return;
    if (type === 'upload') {
      await window.electronAPI!.cancelUpload(taskId);
    } else {
      await window.electronAPI!.cancelDownload(taskId);
    }
    await refresh();
  }, [refresh]);

  const handleOpenFile = useCallback(async (savePath: string) => {
    if (!isElectron()) return;
    await window.electronAPI!.openPath(savePath);
  }, []);

  const handleShowInFolder = useCallback((savePath: string) => {
    if (!isElectron()) return;
    window.electronAPI!.showItemInFolder(savePath);
  }, []);

  const handleDelete = useCallback(async (task: TransferTask, removeFile: boolean) => {
    if (!isElectron()) return;
    setDeleting(true);
    try {
      if (removeFile) {
        const localPath = task.type === 'upload' ? task.filePath : task.savePath;
        if (localPath) {
          try {
            await window.electronAPI!.trashItem(localPath);
          } catch {
            // 文件可能已不存在，忽略错误继续删除任务记录
          }
        }
      }
      await window.electronAPI!.removeTask(task.id);
      await refresh();
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
      setDeleteSourceFile(false);
    }
  }, [refresh]);

  const filteredTasks = tasks.filter((t) => {
    if (filter === 'all') return true;
    if (filter === 'active') return activeStatuses.includes(t.status) || t.status === 'paused';
    return t.type === filter;
  });

  const activeCount = tasks.filter(t => activeStatuses.includes(t.status)).length;

  // 聚合速度
  const totalUploadSpeed = tasks
    .filter(t => t.type === 'upload' && activeStatuses.includes(t.status))
    .reduce((sum, t) => sum + (t.speed || 0), 0);
  const totalDownloadSpeed = tasks
    .filter(t => t.type === 'download' && activeStatuses.includes(t.status))
    .reduce((sum, t) => sum + (t.speed || 0), 0);

  if (!isElectron()) {
    return (
      <div className="flex flex-col items-center justify-center h-full py-20">
        <FileUp className="w-12 h-12 text-stone-300 mb-4" />
        <p className="text-stone-500 text-sm">传输管理功能仅在桌面客户端中可用</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-stone-50/50">
      {/* Header */}
      <div className="px-6 py-3.5 bg-white border-b border-stone-100">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h1 className="text-lg font-semibold text-stone-900">传输管理</h1>
            {activeCount > 0 && (
              <span className="px-2 py-0.5 text-xs font-medium text-primary-600 bg-primary-50 rounded-full">
                {activeCount} 个进行中
              </span>
            )}
          </div>

          {/* 聚合速度总览 */}
          <div className="flex items-center gap-4">
            {totalUploadSpeed > 0 && (
              <div className="flex items-center gap-1.5 px-2.5 py-1 bg-blue-50 rounded-md">
                <ArrowUp className="w-3.5 h-3.5 text-blue-500" />
                <span className="text-sm font-bold text-blue-600 tabular-nums">{formatSpeed(totalUploadSpeed)}</span>
              </div>
            )}
            {totalDownloadSpeed > 0 && (
              <div className="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-50 rounded-md">
                <ArrowDown className="w-3.5 h-3.5 text-emerald-500" />
                <span className="text-sm font-bold text-emerald-600 tabular-nums">{formatSpeed(totalDownloadSpeed)}</span>
              </div>
            )}
            <button
              onClick={() => setTransferSettingsOpen(true)}
              className="p-1.5 text-stone-400 hover:text-stone-700 hover:bg-stone-100 rounded-md cursor-pointer transition-colors"
              title="传输设置"
            >
              <Settings2 className="w-4 h-4" />
            </button>
            <button
              onClick={refresh}
              className="p-1.5 text-stone-400 hover:text-stone-700 hover:bg-stone-100 rounded-md cursor-pointer transition-colors"
              title="刷新"
            >
              <RefreshCw className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Filter tabs */}
      <div className="flex items-center gap-1.5 px-6 py-2.5 bg-white border-b border-stone-100">
        {([
          { key: 'all', label: '全部' },
          { key: 'active', label: '进行中' },
          { key: 'upload', label: '上传' },
          { key: 'download', label: '下载' },
        ] as const).map((tab) => (
          <button
            key={tab.key}
            onClick={() => setFilter(tab.key)}
            className={`px-3 py-1 text-xs rounded-full cursor-pointer transition-all ${
              filter === tab.key
                ? 'bg-stone-800 text-white font-medium'
                : 'text-stone-500 hover:text-stone-800 hover:bg-stone-100'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Task list */}
      <div className="flex-1 overflow-y-auto px-4 py-3">
        {filteredTasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24">
            <div className="w-16 h-16 rounded-full bg-stone-100 flex items-center justify-center mb-4">
              <CheckCircle2 className="w-8 h-8 text-stone-300" />
            </div>
            <p className="text-stone-400 text-sm">暂无传输任务</p>
          </div>
        ) : (
          filteredTasks.map((task) => {
            const cfg = statusConfig[task.status];
            const StatusIcon = cfg.icon;
            const isActive = activeStatuses.includes(task.status);
            const isPaused = task.status === 'paused';
            const isUpload = task.type === 'upload';

            return (
              <div
                key={task.id}
                className="bg-white rounded-lg border border-stone-100 px-4 py-3 mb-2 hover:border-stone-200 hover:shadow-sm transition-all animate-fade-in"
              >
                <div className="flex items-start gap-3">
                  {/* 左侧：图标 + 文件信息 + 进度 */}
                  <div className="flex-1 min-w-0">
                    {/* 文件名行 */}
                    <div className="flex items-center gap-2 mb-1.5">
                      <div className={`w-6 h-6 rounded flex items-center justify-center flex-shrink-0 ${
                        isUpload ? 'bg-blue-50' : 'bg-emerald-50'
                      }`}>
                        {isUpload ? (
                          <ArrowUp className="w-3 h-3 text-blue-500" />
                        ) : (
                          <ArrowDown className="w-3 h-3 text-emerald-500" />
                        )}
                      </div>
                      <p className="text-sm font-medium text-stone-800 truncate flex-1 min-w-0" title={task.fileName}>
                        {task.fileName}
                      </p>
                      {/* 状态标签 - 仅非活跃任务显示 */}
                      {!isActive && !isPaused && (
                        <span className={`text-xs flex items-center gap-1 flex-shrink-0 ${cfg.color}`}>
                          <StatusIcon className="w-3 h-3" />
                          {cfg.label}
                        </span>
                      )}
                    </div>

                    {/* 进度条（活跃/暂停） */}
                    {(isActive || isPaused) && (
                      <div className="flex items-center gap-2 ml-8 mb-1">
                        <div className="flex-1 h-2 bg-stone-100 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full transition-all duration-300 ${
                              task.status === 'failed' ? 'bg-red-500' :
                              task.status === 'paused' ? 'bg-amber-400' :
                              isUpload ? 'bg-blue-500' : 'bg-emerald-500'
                            }`}
                            style={{ width: `${task.progress}%` }}
                          />
                        </div>
                        <span className="text-xs font-bold text-stone-700 tabular-nums w-9 text-right">{task.progress}%</span>
                      </div>
                    )}

                    {/* 底部 meta 信息 */}
                    <div className="flex items-center gap-2 ml-8 text-xs text-stone-400">
                      {(isActive || isPaused) ? (
                        <span className="tabular-nums">{formatSize(task.transferredBytes)} / {formatSize(task.fileSize)}</span>
                      ) : (
                        <>
                          <span className="tabular-nums">{formatSize(task.fileSize)}</span>
                          <span className="text-stone-300">·</span>
                          <span>{formatTime(task.createdAt)}</span>
                        </>
                      )}
                    </div>

                    {/* 错误信息 */}
                    {task.error && (
                      <div className="ml-8 mt-1 text-xs text-red-400 truncate flex items-center gap-1">
                        <AlertCircle className="w-3 h-3 flex-shrink-0" />
                        {task.error}
                      </div>
                    )}
                  </div>

                  {/* 右侧：速度徽章 + 操作按钮 */}
                  <div className="flex flex-col items-end gap-1.5 flex-shrink-0">
                    {/* 速度徽章 - 核心视觉元素 */}
                    {isActive && (
                      <div className={`px-2.5 py-1 rounded-md text-sm font-bold tabular-nums text-white shadow-sm ${
                        isUpload ? 'bg-blue-500' : 'bg-emerald-500'
                      }`}>
                        {isUpload ? '↑ ' : '↓ '}{formatSpeed(task.speed)}
                      </div>
                    )}
                    {isPaused && (
                      <div className="px-2.5 py-1 rounded-md text-sm font-bold text-amber-600 bg-amber-50 border border-amber-200">
                        已暂停
                      </div>
                    )}

                    {/* 操作按钮 */}
                    <div className="flex items-center gap-0.5">
                      {task.status === 'completed' && task.type === 'download' && task.savePath && (
                        <>
                          <button
                            onClick={() => handleOpenFile(task.savePath!)}
                            className="p-1.5 text-stone-400 hover:text-primary-600 hover:bg-primary-50 rounded-md cursor-pointer transition-colors"
                            title="打开文件"
                          >
                            <FileText className="w-4 h-4" />
                          </button>
                          <button
                            onClick={() => handleShowInFolder(task.savePath!)}
                            className="p-1.5 text-stone-400 hover:text-primary-600 hover:bg-primary-50 rounded-md cursor-pointer transition-colors"
                            title="打开所在文件夹"
                          >
                            <FolderOpen className="w-4 h-4" />
                          </button>
                        </>
                      )}
                      {(isActive || isPaused) && (
                        <>
                          {isPaused ? (
                            <button
                              onClick={() => handleResume(task.id, task.type)}
                              className="p-1.5 text-stone-400 hover:text-emerald-600 hover:bg-emerald-50 rounded-md cursor-pointer transition-colors"
                              title="继续"
                            >
                              <Play className="w-4 h-4" />
                            </button>
                          ) : (
                            <button
                              onClick={() => handlePause(task.id, task.type)}
                              className="p-1.5 text-stone-400 hover:text-amber-600 hover:bg-amber-50 rounded-md cursor-pointer transition-colors"
                              title="暂停"
                            >
                              <Pause className="w-4 h-4" />
                            </button>
                          )}
                          <button
                            onClick={() => handleCancel(task.id, task.type)}
                            className="p-1.5 text-stone-400 hover:text-red-600 hover:bg-red-50 rounded-md cursor-pointer transition-colors"
                            title="取消"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </>
                      )}
                      {!isActive && !isPaused && (
                        <button
                          onClick={() => { setDeleteTarget(task); setDeleteSourceFile(false); }}
                          className="p-1.5 text-stone-400 hover:text-red-600 hover:bg-red-50 rounded-md cursor-pointer transition-colors"
                          title="删除"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* 删除确认弹窗 */}
      {deleteTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 animate-fade-in"
          onClick={() => { if (!deleting) setDeleteTarget(null); }}
        >
          <div
            className="bg-white rounded-xl shadow-xl w-[440px] max-w-[90vw] p-5 animate-scale-in"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start gap-3 mb-4">
              <div className="w-10 h-10 rounded-full bg-red-50 flex items-center justify-center flex-shrink-0">
                <Trash2 className="w-5 h-5 text-red-500" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-base font-semibold text-stone-900">删除任务</h3>
                <p className="text-sm text-stone-500 mt-0.5 break-all line-clamp-2" title={deleteTarget.fileName}>
                  {deleteTarget.fileName}
                </p>
              </div>
            </div>

            {((deleteTarget.type === 'upload' && deleteTarget.filePath) ||
              (deleteTarget.type === 'download' && deleteTarget.savePath)) && (
              <label className="flex items-start gap-2.5 p-3 bg-stone-50 rounded-lg cursor-pointer mb-4 hover:bg-stone-100 transition-colors">
                <input
                  type="checkbox"
                  checked={deleteSourceFile}
                  onChange={(e) => setDeleteSourceFile(e.target.checked)}
                  disabled={deleting}
                  className="mt-0.5 w-4 h-4 accent-red-500 flex-shrink-0"
                />
                <span className="text-sm text-stone-700 min-w-0">
                  同时删除本地{deleteTarget.type === 'upload' ? '源文件' : '下载文件'}
                  <span
                    className="block text-xs text-stone-400 break-all line-clamp-2 mt-0.5"
                    title={deleteTarget.type === 'upload' ? deleteTarget.filePath : deleteTarget.savePath}
                  >
                    {deleteTarget.type === 'upload' ? deleteTarget.filePath : deleteTarget.savePath}
                  </span>
                </span>
              </label>
            )}

            <div className="flex justify-end gap-2">
              <button
                onClick={() => { setDeleteTarget(null); setDeleteSourceFile(false); }}
                disabled={deleting}
                className="px-4 py-2 text-sm text-stone-600 hover:bg-stone-100 rounded-lg cursor-pointer transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={() => handleDelete(deleteTarget, deleteSourceFile)}
                disabled={deleting}
                className="px-4 py-2 text-sm text-white bg-red-500 hover:bg-red-600 rounded-lg cursor-pointer transition-colors disabled:opacity-50 flex items-center gap-1.5"
              >
                {deleting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Trash2 className="w-4 h-4" />}
                {deleting ? '删除中...' : '删除'}
              </button>
            </div>
          </div>
        </div>
      )}

      <TransferSettingsDialog open={transferSettingsOpen} onClose={() => setTransferSettingsOpen(false)} />
    </div>
  );
}
