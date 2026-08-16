import { useState, useEffect, useCallback, useRef } from 'react';
import { RefreshCw, FolderSync, Trash2, Play, Square, Plus, HardDrive, Cloud, AlertCircle, FileUp, FileDown, FileX, GitBranch, Info, AlertTriangle, FileEdit, FilePlus, FolderInput, Settings, Wifi, WifiOff, History, BarChart3, ChevronDown, ChevronRight } from 'lucide-react';
import api from '../lib/api';
import { isElectron } from '../lib/electron';
import { useToast } from '../components/ui/Toast';
import { formatDate } from '../lib/utils';
import type { SyncRootVO, FileNode, SyncExclusionVO, SyncHistoryEntry, SyncStats } from '../types';

interface SyncLogEntry {
  type: 'upload' | 'download' | 'delete' | 'conflict' | 'info' | 'error' | 'move' | 'copy' | 'rename' | 'create';
  message: string;
  detail?: string;
  time: string;
}

const LOG_CONFIG = {
  upload:   { icon: FileUp,     color: 'text-primary-600',   bg: 'bg-primary-500/10',   label: '上传' },
  download: { icon: FileDown,   color: 'text-green-600 dark:text-green-400',     bg: 'bg-green-500/15',     label: '下载' },
  delete:   { icon: FileX,      color: 'text-red-600 dark:text-red-400',       bg: 'bg-red-500/15',        label: '删除' },
  create:   { icon: FilePlus,   color: 'text-primary-600',   bg: 'bg-primary-500/10',    label: '新建' },
  rename:   { icon: FileEdit,   color: 'text-amber-600 dark:text-amber-400',     bg: 'bg-amber-500/15',      label: '重命名' },
  move:     { icon: FolderInput,color: 'text-blue-600',      bg: 'bg-blue-500/15',       label: '移动' },
  copy:     { icon: FolderInput,color: 'text-muted',    bg: 'bg-surface-2',     label: '复制' },
  conflict: { icon: GitBranch,  color: 'text-amber-600 dark:text-amber-400',     bg: 'bg-amber-500/15',      label: '冲突' },
  info:     { icon: Info,       color: 'text-muted',     bg: 'bg-surface-2',     label: '信息' },
  error:    { icon: AlertTriangle, color: 'text-red-600 dark:text-red-400',   bg: 'bg-red-500/15',        label: '错误' },
};

const FILTER_TABS = [
  { key: 'all',     label: '全部' },
  { key: 'upload',   label: '上传' },
  { key: 'download', label: '下载' },
  { key: 'delete',   label: '删除' },
  { key: 'create',   label: '新建' },
  { key: 'conflict', label: '冲突' },
];

export default function SyncPage() {
  const { showToast } = useToast();
  const [roots, setRoots] = useState<SyncRootVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRegister, setShowRegister] = useState(false);
  const [showExclusions, setShowExclusions] = useState<string | null>(null);
  const [exclusions, setExclusions] = useState<SyncExclusionVO[]>([]);
  const [exclusionInput, setExclusionInput] = useState('');
  const [wsConnected, setWsConnected] = useState(false);
  const [syncStatus, setSyncStatus] = useState<Record<string, boolean>>({});
  const [logs, setLogs] = useState<SyncLogEntry[]>([]);
  const [logFilter, setLogFilter] = useState<string>('all');
  const logEndRef = useRef<HTMLDivElement>(null);
  const [expandedRoot, setExpandedRoot] = useState<string | null>(null);
  const [rootStats, setRootStats] = useState<Record<string, SyncStats>>({});
  const [rootHistory, setRootHistory] = useState<Record<string, SyncHistoryEntry[]>>({});
  const [historyFilter, setHistoryFilter] = useState<string>('all');

  const fetchRoots = useCallback(async () => {
    setLoading(true);
    try {
      if (isElectron()) {
        const data = await window.electronAPI!.syncListRoots();
        setRoots(data || []);
        const status = await window.electronAPI!.syncStatus();
        setSyncStatus(status || {});
      }
    } catch {
      setRoots([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRoots();
  }, [fetchRoots]);

  useEffect(() => {
    if (!isElectron()) return;
    const unsubscribe = window.electronAPI!.onSyncEvent((evt) => {
      if (evt.event === 'ws_status' && evt.data) {
        setWsConnected((evt.data as { connected: boolean }).connected);
        return;
      }
      if (evt.event === 'log' && evt.data) {
        const logData = evt.data as SyncLogEntry;
        setLogs((prev) => [...prev, logData].slice(-300));
        if (logData.type === 'conflict') {
          showToast('检测到文件冲突，已保留两份副本', 'warning');
        }
        return;
      }
      if (evt.event === 'started') {
        setLogs((prev) => [...prev, { type: 'info', message: '同步引擎已启动', time: new Date().toISOString() } as SyncLogEntry].slice(-300));
      } else if (evt.event === 'stopped') {
        setLogs((prev) => [...prev, { type: 'info', message: '同步引擎已停止', time: new Date().toISOString() } as SyncLogEntry].slice(-300));
      }
    });
    return unsubscribe;
  }, [showToast]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  const handleStart = async (rootId: string, cloudFolderNodeId: string, localPath: string) => {
    if (!isElectron()) return;
    try {
      await window.electronAPI!.syncStart(rootId, cloudFolderNodeId, localPath);
      setSyncStatus((prev) => ({ ...prev, [rootId]: true }));
      showToast('同步已启动', 'success');
      fetchRoots();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '启动同步失败', 'error');
    }
  };

  const handleStop = async (rootId: string) => {
    if (!isElectron()) return;
    try {
      await window.electronAPI!.syncStop(rootId);
      setSyncStatus((prev) => ({ ...prev, [rootId]: false }));
      showToast('同步已停止', 'success');
    } catch {
      showToast('停止同步失败', 'error');
    }
  };

  const handleConflictStrategy = async (rootId: string, strategy: string) => {
    if (!isElectron()) return;
    try {
      await window.electronAPI!.syncUpdateConflictStrategy(rootId, strategy);
      setRoots((prev) => prev.map((r) => r.id === rootId ? { ...r, conflictStrategy: strategy } : r));
      showToast('Conflict strategy updated', 'success');
    } catch {
      showToast('Failed to update conflict strategy', 'error');
    }
  };

  const handleShowExclusions = async (rootId: string) => {
    if (!isElectron()) return;
    setShowExclusions(rootId);
    setExclusionInput('');
    try {
      const data = await window.electronAPI!.syncListExclusions(rootId);
      setExclusions(data || []);
    } catch {
      setExclusions([]);
    }
  };

  const handleAddExclusion = async () => {
    if (!showExclusions || !exclusionInput.trim() || !isElectron()) return;
    try {
      await window.electronAPI!.syncAddExclusion(showExclusions, exclusionInput.trim());
      const data = await window.electronAPI!.syncListExclusions(showExclusions);
      setExclusions(data || []);
      setExclusionInput('');
    } catch {
      showToast('Failed to add exclusion', 'error');
    }
  };

  const handleRemoveExclusion = async (exclusionId: string) => {
    if (!showExclusions || !isElectron()) return;
    try {
      await window.electronAPI!.syncRemoveExclusion(showExclusions, exclusionId);
      const data = await window.electronAPI!.syncListExclusions(showExclusions);
      setExclusions(data || []);
    } catch {
      showToast('Failed to remove exclusion', 'error');
    }
  };

  const handleExpandRoot = async (rootId: string) => {
    if (expandedRoot === rootId) {
      setExpandedRoot(null);
      return;
    }
    setExpandedRoot(rootId);
    if (isElectron()) {
      try {
        const [stats, history] = await Promise.all([
          window.electronAPI!.syncGetStats(rootId),
          window.electronAPI!.syncGetHistory(rootId),
        ]);
        setRootStats((prev) => ({ ...prev, [rootId]: stats }));
        setRootHistory((prev) => ({ ...prev, [rootId]: history }));
      } catch { /* ignore */ }
    }
  };

  const handleDelete = async (rootId: string) => {
    if (!isElectron()) return;
    try {
      await window.electronAPI!.syncDeleteRoot(rootId);
      showToast('已删除同步根', 'success');
      fetchRoots();
    } catch {
      showToast('删除失败', 'error');
    }
  };

  if (!isElectron()) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center py-20">
        <AlertCircle className="w-12 h-12 text-muted mb-4" />
        <h2 className="text-lg font-semibold text-muted mb-2">需要桌面客户端</h2>
        <p className="text-sm text-muted max-w-md">
          文件同步功能仅在 星云盘 桌面客户端中可用。请下载安装 PC 客户端后使用。
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-surface">
      <div className="flex items-center justify-between px-5 py-2.5 border-b border-border">
        <div className="flex items-center gap-2">
          <FolderSync className="w-5 h-5 text-primary-600" />
          <h1 className="text-base font-semibold text-fg">文件同步</h1>
        </div>
        <div className="flex items-center gap-2">
          <span className="flex items-center gap-1 text-xs " title={wsConnected ? "Real-time connected" : "Offline - polling"}>
            {wsConnected ? <Wifi className="w-3.5 h-3.5 text-green-600" /> : <WifiOff className="w-3.5 h-3.5 text-muted" />}
            <span className={wsConnected ? "text-green-600" : "text-muted"}>{wsConnected ? "Realtime" : "Polling"}</span>
          </span>
          <button onClick={fetchRoots} className="btn-ghost">
            <RefreshCw className="w-4 h-4" aria-hidden />
            <span>刷新</span>
          </button>
          <button onClick={() => setShowRegister(true)} className="btn-primary">
            <Plus className="w-4 h-4" />
            <span>添加同步</span>
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-5">
        {/* Sync roots */}
        {loading ? (
          <div className="text-center py-12 text-muted text-sm">加载中…</div>
        ) : roots.length === 0 ? (
          <div className="text-center py-20">
            <FolderSync className="w-12 h-12 text-muted mx-auto mb-4" />
            <h3 className="text-sm font-semibold text-muted mb-1">还没有同步根</h3>
            <p className="text-sm text-muted">点击「添加同步」将本地文件夹与云端同步</p>
          </div>
        ) : (
          <div className="space-y-3 max-w-3xl mb-6">
            {roots.map((root) => {
              const isActive = syncStatus[root.id] ?? false;
              return (
                <div key={root.id} className="rounded-lg border border-border hover:bg-surface-2">
              <div className="flex items-center gap-4 p-4 cursor-pointer" onClick={() => handleExpandRoot(root.id)}>
                {expandedRoot === root.id ? <ChevronDown className="w-4 h-4 text-muted flex-shrink-0" /> : <ChevronRight className="w-4 h-4 text-muted flex-shrink-0" />}
                  <div className={'w-10 h-10 rounded-lg flex items-center justify-center ' + (isActive ? 'bg-primary-500/10' : 'bg-surface-2')}>
                    <HardDrive className={'w-5 h-5 ' + (isActive ? 'text-primary-600' : 'text-muted')} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <Cloud className="w-4 h-4 text-muted" />
                      <span className="text-sm font-medium text-fg truncate">{root.cloudFolderName || '云端文件夹'}</span>
                      <span className={'text-xs px-1.5 py-0.5 rounded ' + (isActive ? 'bg-green-500/15 text-green-700' : 'bg-surface-2 text-muted')}>
                        {isActive ? '同步中' : '已停止'}
                      </span>
                    </div>
                    <div className="text-xs text-muted truncate">
                      {root.localPathHint || '未设置本地路径'} · 上次同步 {formatDate(root.updatedAt)}
                    </div>
                  </div>
                  <div className="flex items-center gap-1">
                    <select
                      value={root.conflictStrategy || "keep_both"}
                      onChange={(e) => handleConflictStrategy(root.id, e.target.value)}
                      className="text-xs border border-border rounded px-1.5 py-1 bg-surface text-fg"
                      title="Conflict resolution strategy"
                    >
                      <option value="keep_both">Keep Both</option>
                      <option value="latest_wins">Latest Wins</option>
                      <option value="server_wins">Server Wins</option>
                      <option value="local_wins">Local Wins</option>
                    </select>
                    <button onClick={() => handleShowExclusions(root.id)} className="btn-ghost" title="Selective sync">
                      <Settings className="w-4 h-4" aria-hidden />
                    </button>
                    {isActive ? (
                      <button onClick={() => handleStop(root.id)} className="btn-ghost text-amber-600 dark:text-amber-400 hover:bg-amber-500/10">
                        <Square className="w-4 h-4" aria-hidden />
                        <span>停止</span>
                      </button>
                    ) : (
                      <button onClick={() => handleStart(root.id, root.cloudFolderNodeId, root.localPathHint || '')} className="btn-ghost text-green-600 dark:text-green-400 hover:bg-green-500/10">
                        <Play className="w-4 h-4" aria-hidden />
                        <span>启动</span>
                      </button>
                    )}
                    <button onClick={() => handleDelete(root.id)} className="btn-ghost text-red-600 dark:text-red-400 hover:bg-red-500/10" aria-label="删除">
                      <Trash2 className="w-4 h-4" aria-hidden />
                    </button>
                  </div>
                </div>
                {/* 状态看板 + 历史记录（展开时） */}
                {expandedRoot === root.id && (
                  <div className="mt-2 ml-14 p-3 rounded-lg bg-surface-2 border border-border space-y-3">
                    {/* 统计卡 */}
                    <div className="flex items-center gap-4 text-xs">
                      <span className="flex items-center gap-1.5"><BarChart3 className="w-3.5 h-3.5 text-primary-600" /><span className="text-muted">已同步</span> <span className="font-semibold text-fg">{rootStats[root.id]?.synced ?? 0}</span></span>
                      <span className="flex items-center gap-1.5"><AlertTriangle className="w-3.5 h-3.5 text-amber-600" /><span className="text-muted">冲突</span> <span className="font-semibold text-fg">{rootStats[root.id]?.conflict ?? 0}</span></span>
                      <span className="flex items-center gap-1.5"><AlertCircle className="w-3.5 h-3.5 text-red-600" /><span className="text-muted">错误</span> <span className="font-semibold text-fg">{rootStats[root.id]?.error ?? 0}</span></span>
                      <span className="flex items-center gap-1.5"><Settings className="w-3.5 h-3.5 text-muted" /><span className="text-muted">已排除</span> <span className="font-semibold text-fg">{rootStats[root.id]?.excluded ?? 0}</span></span>
                    </div>
                    {/* 历史记录 */}
                    <div>
                      <div className="flex items-center gap-2 mb-2">
                        <History className="w-3.5 h-3.5 text-muted" />
                        <span className="text-xs font-medium text-muted">同步历史</span>
                        <div className="flex gap-1 ml-auto">
                          {['all','upload','download','delete','conflict'].map((k) => (
                            <button key={k} onClick={() => setHistoryFilter(k)} className={'text-xs px-1.5 py-0.5 rounded ' + (historyFilter === k ? 'bg-primary-500/10 text-primary-600' : 'text-muted hover:bg-surface')}>{k === 'all' ? '全部' : k}</button>
                          ))}
                        </div>
                      </div>
                      <div className="max-h-40 overflow-y-auto space-y-1">
                        {(rootHistory[root.id] ?? []).filter((h) => historyFilter === 'all' || h.action === historyFilter).length === 0 ? (
                          <div className="text-xs text-muted text-center py-3">暂无历史记录</div>
                        ) : (
                          (rootHistory[root.id] ?? []).filter((h) => historyFilter === 'all' || h.action === historyFilter).map((h) => (
                            <div key={h.id} className="flex items-center gap-2 text-xs py-1">
                              <span className={'w-1.5 h-1.5 rounded-full flex-shrink-0 ' + (h.status === 'success' ? 'bg-green-500' : 'bg-red-500')} />
                              <span className="text-muted w-14 flex-shrink-0">{h.action}</span>
                              <span className="text-fg truncate flex-1">{h.fileName || h.relPath}</span>
                              <span className="text-muted flex-shrink-0">{new Date(h.createdAt).toLocaleTimeString('zh-CN', { hour12: false })}</span>
                            </div>
                          ))
                        )}
                      </div>
                    </div>
                  </div>
                )}
                </div>
              );
            })}
          </div>
        )}

        {/* Sync activity log */}
        <div className="max-w-3xl">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-muted">同步日志</h3>
            <div className="flex items-center gap-3">
              {logs.length > 0 && (
                <button onClick={() => setLogs([])} className="text-xs text-muted hover:text-fg cursor-pointer transition-colors">
                  清空
                </button>
              )}
            </div>
          </div>

          {logs.length > 0 && (
            <div className="flex items-center gap-1 mb-3">
              {FILTER_TABS.map((tab) => {
                const count = tab.key === 'all' ? logs.length : logs.filter((l) => l.type === tab.key).length;
                if (tab.key !== 'all' && count === 0) return null;
                return (
                  <button
                    key={tab.key}
                    onClick={() => setLogFilter(tab.key)}
                    className={'px-2.5 py-1 text-xs rounded-md transition-colors cursor-pointer ' +
                      (logFilter === tab.key ? 'bg-primary-500/10 text-primary-600 font-medium' : 'text-muted hover:bg-surface-2')}
                  >
                    {tab.label}
                    {count > 0 && <span className="ml-1 opacity-60">{count}</span>}
                  </button>
                );
              })}
            </div>
          )}

          {logs.length === 0 ? (
            <div className="text-center py-12 bg-surface-2 rounded-lg border border-border">
              <FolderSync className="w-10 h-10 text-muted mx-auto mb-3" />
              <p className="text-sm text-muted">暂无同步日志，启动同步后这里会显示文件操作记录</p>
            </div>
          ) : (
            <div className="space-y-1.5 max-h-[500px] overflow-y-auto">
              {logs.filter((log) => logFilter === 'all' || log.type === logFilter).map((log, i) => {
                const cfg = LOG_CONFIG[log.type] ?? LOG_CONFIG.info;
                const Icon = cfg.icon;
                const time = new Date(log.time).toLocaleTimeString('zh-CN', { hour12: false });
                return (
                  <div key={i} className="flex items-center gap-3 p-2.5 rounded-md hover:bg-surface-2 transition-colors">
                    <div className={'w-7 h-7 rounded-md flex items-center justify-center flex-shrink-0 ' + cfg.bg}>
                      <Icon className={'w-3.5 h-3.5 ' + cfg.color} />
                    </div>
                    <span className={'text-xs font-medium flex-shrink-0 ' + cfg.color}>{cfg.label}</span>
                    <span className="text-sm text-fg truncate flex-1">{log.message}</span>
                    <span className="text-xs text-muted flex-shrink-0">{time}</span>
                  </div>
                );
              })}
              <div ref={logEndRef} />
            </div>
          )}
        </div>
      </div>

      {showExclusions && (
        <div className="modal-overlay" onClick={() => setShowExclusions(null)}>
          <div className="modal-content w-[480px] max-w-[92vw] p-6" onClick={(e) => e.stopPropagation()}>
            <h2 className="text-lg font-semibold text-fg mb-4">Selective Sync</h2>
            <p className="text-sm text-muted mb-4">Excluded paths will not be synced between local and cloud.</p>

            <div className="flex items-center gap-2 mb-4">
              <input
                type="text"
                value={exclusionInput}
                onChange={(e) => setExclusionInput(e.target.value)}
                placeholder="/subfolder to exclude..."
                className="input-field flex-1 text-sm"
                onKeyDown={(e) => e.key === 'Enter' && handleAddExclusion()}
              />
              <button onClick={handleAddExclusion} className="btn-primary text-sm">Add</button>
            </div>

            <div className="max-h-60 overflow-y-auto space-y-1.5 mb-4">
              {exclusions.length === 0 ? (
                <div className="text-sm text-muted text-center py-4">No exclusions - all paths sync</div>
              ) : (
                exclusions.map((excl) => (
                  <div key={excl.id} className="flex items-center justify-between p-2 rounded-md bg-surface-2">
                    <span className="text-sm text-fg truncate">{excl.relativePath}</span>
                    <button
                      onClick={() => handleRemoveExclusion(excl.id)}
                      className="text-red-600 hover:text-red-700 flex-shrink-0 ml-2"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))
              )}
            </div>

            <div className="flex justify-end">
              <button onClick={() => setShowExclusions(null)} className="btn-secondary">Close</button>
            </div>
          </div>
        </div>
      )}

      {showRegister && (
        <RegisterDialog onClose={() => setShowRegister(false)} onSuccess={fetchRoots} />
      )}
    </div>
  );
}

function RegisterDialog({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [folders, setFolders] = useState<FileNode[]>([]);
  const [selectedFolder, setSelectedFolder] = useState<FileNode | null>(null);
  const [localPath, setLocalPath] = useState('');

  useEffect(() => {
    api.get<{ records?: FileNode[] }>('/file/list', { params: { parentId: '0', page: 1, size: 100 } })
      .then((res) => setFolders((res?.records || []).filter((f: FileNode) => f.nodeType === 0)))
      .catch(() => {});
  }, []);

  const handleSelectLocalPath = async () => {
    if (!isElectron()) return;
    const paths = await window.electronAPI!.selectFolder();
    if (paths.length > 0) setLocalPath(paths[0]);
  };

  const handleRegister = async () => {
    if (!selectedFolder) { showToast('请选择云端文件夹', 'warning'); return; }
    if (!localPath) { showToast('请选择本地同步目录', 'warning'); return; }
    setLoading(true);
    try {
      await window.electronAPI!.syncRegister(selectedFolder.id, localPath);
      showToast('同步根注册成功，已自动启动同步', 'success');
      onSuccess();
      onClose();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '注册失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-[480px] max-w-[92vw] p-6" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-semibold text-fg mb-5">添加文件同步</h2>

        <label className="block text-sm font-medium text-muted mb-2">云端文件夹</label>
        <div className="max-h-48 overflow-y-auto border border-border rounded-md mb-4">
          {folders.length === 0 ? (
            <div className="p-3 text-sm text-muted text-center">没有可选文件夹</div>
          ) : (
            folders.map((f) => (
              <button
                key={f.id}
                onClick={() => setSelectedFolder(f)}
                className={'w-full flex items-center gap-2 px-3 py-2 text-sm cursor-pointer transition-colors ' + (selectedFolder?.id === f.id ? 'bg-primary-500/10 text-primary-600' : 'hover:bg-surface-2 text-muted')}
              >
                <Cloud className="w-4 h-4 text-muted" />
                <span>{f.name}</span>
              </button>
            ))
          )}
        </div>

        <label className="block text-sm font-medium text-muted mb-2">本地同步目录</label>
        <div className="flex items-center gap-2 mb-5">
          <input type="text" value={localPath} readOnly placeholder="点击右侧按钮选择..." className="input-field flex-1" />
          <button onClick={handleSelectLocalPath} className="btn-secondary flex-shrink-0">
            <HardDrive className="w-4 h-4" />
            选择
          </button>
        </div>

        <div className="p-3 rounded-lg bg-amber-500/15 border border-amber-200 text-xs text-amber-800 mb-5">
          同步启动后，本地与云端的双向变更将自动同步。冲突文件会保留两份副本。
        </div>

        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleRegister} disabled={loading} className="btn-primary">
            {loading ? '注册中…' : '注册并同步'}
          </button>
        </div>
      </div>
    </div>
  );
}
