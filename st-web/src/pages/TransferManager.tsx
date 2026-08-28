import { useState, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ArrowUp, ArrowDown, Pause, Play, CheckCircle2,
  Activity, Loader2, Trash2,
  FileUp, RefreshCw, Settings2, Inbox, Clock, ArrowUpDown,
} from 'lucide-react';
import { isElectron } from '../lib/electron';
import { isCapacitor } from '../lib/runtime';
import { useMobile } from '../hooks/useMobile';
import { formatSize } from '../lib/utils';
import type { TransferTask } from '../types';
import TransferSettingsDialog from '../components/TransferSettingsDialog';
import TransferTaskCard from '../components/transfer/TransferTaskCard';
import TransferHistory, { appendHistory } from '../components/transfer/TransferHistory';

type FilterKey = 'all' | 'active' | 'completed' | 'history';

const activeStatuses = ['uploading', 'downloading', 'hashing', 'merging', 'pending'];

function formatSpeed(speed: number): string {
  if (speed <= 0) return '0 B/s';
  return `${formatSize(speed)}/s`;
}

/** 统计指标卡：彩色图标章 + 大数字 + 标签，可点击的卡片会切换过滤 tab */
function StatCard({ icon: Icon, label, value, tone, active, onClick }: {
  icon: typeof Activity;
  label: string;
  value: string;
  tone: string;
  active?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      onClick={onClick}
      disabled={!onClick}
      className={`flex items-center gap-3 px-3.5 py-2.5 bg-surface rounded-xl border text-left transition-all duration-200 ${
        active
          ? 'border-primary-300 shadow-primary'
          : 'border-border hover:shadow-card hover:-translate-y-0.5'
      } ${onClick ? 'cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring' : 'cursor-default'}`}
    >
      <div className={`w-9 h-9 rounded-lg ${tone} flex items-center justify-center flex-shrink-0`}>
        <Icon className="w-4 h-4" aria-hidden />
      </div>
      <div className="min-w-0">
        <p className="text-base font-bold text-fg tabular-nums leading-tight truncate">{value}</p>
        <p className="text-[11px] text-muted leading-tight">{label}</p>
      </div>
    </button>
  );
}

export default function TransferManager() {
  const [searchParams] = useSearchParams();
  const [tasks, setTasks] = useState<TransferTask[]>([]);
  const [filter, setFilter] = useState<FilterKey>('active');
  const [deleteTarget, setDeleteTarget] = useState<TransferTask | null>(null);
  const [deleteSourceFile, setDeleteSourceFile] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [transferSettingsOpen, setTransferSettingsOpen] = useState(false);

  // 悬浮窗"简易限速"入口：跳转到 ?settings=1 时自动弹出传输设置对话框
  useEffect(() => {
    if (searchParams.get('settings') === '1') {
      setTransferSettingsOpen(true);
    }
  }, [searchParams]);

  // 初始加载
  useEffect(() => {
    if (!isElectron()) return;
    window.electronAPI!.getTasks().then(setTasks);
  }, []);

  // 监听任务更新
  useEffect(() => {
    if (!isElectron()) return;
    const unsubscribe = window.electronAPI!.onTaskUpdate((updatedTask: TransferTask) => {
      // 已完成任务写入本地传输历史
      appendHistory(updatedTask);
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

  const handleDeleteClick = useCallback((task: TransferTask) => {
    setDeleteTarget(task);
    setDeleteSourceFile(false);
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

  // 派生统计与过滤结果（useMemo 避免每次渲染重算聚合）
  const { filteredTasks, activeCount, completedCount, totalUploadSpeed, totalDownloadSpeed } = useMemo(() => {
    const filtered = tasks.filter((t) => {
      if (filter === 'active') return activeStatuses.includes(t.status) || t.status === 'paused';
      if (filter === 'completed') return t.status === 'completed';
      return true;
    });
    return {
      filteredTasks: filtered,
      activeCount: tasks.filter(t => activeStatuses.includes(t.status)).length,
      completedCount: tasks.filter(t => t.status === 'completed').length,
      totalUploadSpeed: tasks
        .filter(t => t.type === 'upload' && activeStatuses.includes(t.status))
        .reduce((sum, t) => sum + (t.speed || 0), 0),
      totalDownloadSpeed: tasks
        .filter(t => t.type === 'download' && activeStatuses.includes(t.status))
        .reduce((sum, t) => sum + (t.speed || 0), 0),
    };
  }, [tasks, filter]);

  const isMobileView = useMobile();
  if (!isElectron()) {
    return (
      <div className="flex flex-col items-center justify-center h-full py-20 px-6 text-center">
        <FileUp className="w-12 h-12 text-muted mb-4" />
        {isCapacitor() ? (
          <>
            <p className="text-fg text-sm font-medium mb-1">传输管理即将上线</p>
            <p className="text-muted text-xs">Capacitor 原生传输能力开发中，当前请使用文件页上传</p>
          </>
        ) : isMobileView ? (
          <>
            <p className="text-fg text-sm font-medium mb-1">浏览器传输模式</p>
            <p className="text-muted text-xs leading-relaxed">上传任务在浏览器前台运行，请保持页面开启。切换应用或锁屏可能导致传输中断。</p>
          </>
        ) : (
          <p className="text-muted text-sm">传输管理功能仅在桌面客户端中可用</p>
        )}
      </div>
    );
  }

  const emptyCopy: Record<FilterKey, { title: string; desc: string }> = {
    all: { title: '暂无传输任务', desc: '上传或下载的文件会显示在这里' },
    active: { title: '没有进行中的任务', desc: '当前所有传输都已完成或已清空' },
    completed: { title: '还没有完成的任务', desc: '传输完成后会出现在这里' },
    history: { title: '暂无传输历史', desc: '传输完成后会自动记录到历史' },
  };

  return (
    <div className="flex flex-col h-full bg-surface-2/50">
      {/* Header：品牌图标章 + 标题 + 副标题统计 */}
      <div className="px-6 pt-4 pb-3 bg-surface border-b border-border">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center flex-shrink-0 shadow-primary">
              <ArrowUpDown className="w-5 h-5 text-white" aria-hidden />
            </div>
            <div className="min-w-0">
              <h1 className="text-lg font-semibold text-fg leading-tight">传输管理</h1>
              <p className="text-xs text-muted mt-0.5">
                {activeCount > 0
                  ? <><span className="text-primary-600 dark:text-primary-400 font-medium tabular-nums">{activeCount}</span> 个任务传输中 · 已完成 <span className="tabular-nums">{completedCount}</span> 个</>
                  : `共 ${tasks.length} 个任务，已完成 ${completedCount} 个`}
              </p>
            </div>
          </div>

          {/* 操作按钮组 */}
          <div className="flex items-center gap-1.5 flex-shrink-0">
            {isElectron() && (
              <>
                <button
                  onClick={() => window.electronAPI!.pauseAllTransfers()}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-muted hover:text-fg bg-surface border border-border hover:bg-surface-2 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="暂停全部任务"
                >
                  <Pause className="w-3.5 h-3.5" aria-hidden />
                  全部暂停
                </button>
                <button
                  onClick={() => window.electronAPI!.resumeAllTransfers()}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-muted hover:text-fg bg-surface border border-border hover:bg-surface-2 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="开始全部任务"
                >
                  <Play className="w-3.5 h-3.5" aria-hidden />
                  全部开始
                </button>
                <button
                  onClick={() => {
                    window.electronAPI!.showMiniWindow();
                    // 找回：若悬浮窗存在但在屏幕外，直接复位到右下角可见位置
                    window.electronAPI!.resetMiniWindowPosition();
                  }}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-muted hover:text-fg bg-surface border border-border hover:bg-surface-2 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  title="显示/找回桌面传输悬浮窗（复位到右下角）"
                >
                  悬浮窗
                </button>
              </>
            )}
            <button
              onClick={() => setTransferSettingsOpen(true)}
              className="p-2 text-muted hover:text-fg bg-surface border border-border hover:bg-surface-2 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              title="传输设置" aria-label="传输设置"
            >
              <Settings2 className="w-4 h-4" aria-hidden />
            </button>
            <button
              onClick={refresh}
              className="p-2 text-muted hover:text-fg bg-surface border border-border hover:bg-surface-2 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              title="刷新" aria-label="刷新"
            >
              <RefreshCw className="w-4 h-4" aria-hidden />
            </button>
          </div>
        </div>

        {/* 统计指标卡片区 */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 mt-3.5">
          <StatCard icon={Activity} label="进行中" value={String(activeCount)} tone="bg-primary-500/15 text-primary-600 dark:text-primary-400" active={filter === 'active'} onClick={() => setFilter('active')} />
          <StatCard icon={ArrowUp} label="上传速度" value={formatSpeed(totalUploadSpeed)} tone="bg-blue-500/15 text-blue-500" />
          <StatCard icon={ArrowDown} label="下载速度" value={formatSpeed(totalDownloadSpeed)} tone="bg-emerald-500/15 text-emerald-500" />
          <StatCard icon={CheckCircle2} label="已完成" value={String(completedCount)} tone="bg-emerald-500/15 text-emerald-600 dark:text-emerald-400" active={filter === 'completed'} onClick={() => setFilter('completed')} />
        </div>
      </div>

      {/* Segmented tabs（带图标 + 数量徽标） */}
      <div className="flex items-center gap-2 px-6 py-2.5 bg-surface border-b border-border">
        {([
          { key: 'all', label: '全部', icon: Inbox, count: tasks.length },
          { key: 'active', label: '进行中', icon: Activity, count: activeCount },
          { key: 'completed', label: '已完成', icon: CheckCircle2, count: completedCount },
          { key: 'history', label: '历史', icon: Clock, count: 0 },
        ] as const).map((tab) => {
          const TabIcon = tab.icon;
          const isActiveTab = filter === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => setFilter(tab.key)}
              aria-pressed={isActiveTab}
              className={`flex items-center gap-1.5 px-3.5 py-1.5 text-xs rounded-full cursor-pointer transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                isActiveTab
                  ? 'bg-primary-600 text-white font-medium shadow-primary'
                  : 'text-muted hover:text-fg hover:bg-surface-2'
              }`}
            >
              <TabIcon className="w-3.5 h-3.5" aria-hidden />
              {tab.label}
              <span className={`px-1.5 min-w-[18px] text-center text-[10px] font-semibold rounded-full tabular-nums ${
                isActiveTab ? 'bg-white/25 text-white' : 'bg-surface-2 text-muted'
              }`}>
                {tab.count}
              </span>
            </button>
          );
        })}
      </div>

      {/* Task list */}
      <div className="flex-1 overflow-y-auto px-5 py-3">
        {filter === 'history' ? (
          <TransferHistory />
        ) : filteredTasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 animate-fade-in">
            <div className="relative mb-5">
              <div className="absolute inset-0 rounded-full bg-primary-500/10 blur-xl scale-125" aria-hidden />
              <div className="relative w-16 h-16 rounded-full bg-gradient-to-br from-primary-50 to-primary-100 dark:from-primary-500/15 dark:to-primary-500/5 border border-primary-100 dark:border-primary-500/20 flex items-center justify-center">
                {filter === 'active' ? (
                  <Clock className="w-7 h-7 text-primary-500" aria-hidden />
                ) : filter === 'completed' ? (
                  <CheckCircle2 className="w-7 h-7 text-primary-500" aria-hidden />
                ) : (
                  <Inbox className="w-7 h-7 text-primary-500" aria-hidden />
                )}
              </div>
            </div>
            <p className="text-sm font-medium text-fg mb-1">{emptyCopy[filter].title}</p>
            <p className="text-xs text-muted mb-5">{emptyCopy[filter].desc}</p>
            <button
              onClick={refresh}
              className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-primary-600 dark:text-primary-400 bg-primary-500/10 hover:bg-primary-500/15 rounded-lg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden />
              刷新列表
            </button>
          </div>
        ) : (
          filteredTasks.map((task) => (
            <TransferTaskCard
              key={task.id}
              task={task}
              onPause={handlePause}
              onResume={handleResume}
              onCancel={handleCancel}
              onOpenFile={handleOpenFile}
              onShowInFolder={handleShowInFolder}
              onDelete={handleDeleteClick}
            />
          ))
        )}
      </div>

      {/* 删除确认弹窗 */}
      {deleteTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm animate-fade-in"
          onClick={() => { if (!deleting) setDeleteTarget(null); }}
        >
          <div
            className="bg-surface rounded-xl shadow-xl w-[440px] max-w-[90vw] p-5 animate-scale-in"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start gap-3 mb-4">
              <div className="w-10 h-10 rounded-full bg-red-500/15 flex items-center justify-center flex-shrink-0">
                <Trash2 className="w-5 h-5 text-red-500" aria-hidden />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-base font-semibold text-fg">删除任务</h3>
                <p className="text-sm text-muted mt-0.5 break-all line-clamp-2" title={deleteTarget.fileName}>
                  {deleteTarget.fileName}
                </p>
              </div>
            </div>

            {((deleteTarget.type === 'upload' && deleteTarget.filePath) ||
              (deleteTarget.type === 'download' && deleteTarget.savePath)) && (
              <label className="flex items-start gap-2.5 p-3 bg-surface-2 rounded-lg cursor-pointer mb-4 hover:bg-surface-2 transition-colors">
                <input
                  type="checkbox"
                  checked={deleteSourceFile}
                  onChange={(e) => setDeleteSourceFile(e.target.checked)}
                  disabled={deleting}
                  className="mt-0.5 w-4 h-4 accent-red-500 flex-shrink-0"
                />
                <span className="text-sm text-muted min-w-0">
                  同时删除本地{deleteTarget.type === 'upload' ? '源文件' : '下载文件'}
                  <span
                    className="block text-xs text-muted break-all line-clamp-2 mt-0.5"
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
                className="px-4 py-2 text-sm text-muted hover:bg-surface-2 rounded-lg cursor-pointer transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={() => handleDelete(deleteTarget, deleteSourceFile)}
                disabled={deleting}
                className="px-4 py-2 text-sm text-white bg-red-500 hover:bg-red-600 rounded-lg cursor-pointer transition-colors disabled:opacity-50 flex items-center gap-1.5"
              >
                {deleting ? <Loader2 className="w-4 h-4 animate-spin" aria-hidden /> : <Trash2 className="w-4 h-4" aria-hidden />}
                {deleting ? '删除中…' : '删除'}
              </button>
            </div>
          </div>
        </div>
      )}

      <TransferSettingsDialog open={transferSettingsOpen} onClose={() => setTransferSettingsOpen(false)} />
    </div>
  );
}
