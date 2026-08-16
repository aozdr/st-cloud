import { useState, useEffect, useCallback, useRef } from 'react';
import { X, File, Folder, Download, Archive, ChevronDown, ChevronRight, FolderClosed, FolderOpen, FolderPlus, Loader2 } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { usePrompt } from '../ui/PromptDialog';
import { formatSize, cn } from '../../lib/utils';
import type { FileNode, FileTreeNode } from '../../types';

interface ArchiveEntry {
  name: string;
  fileName: string;
  size: number;
  isDirectory: boolean;
}

interface Props {
  file: FileNode;
  onClose: () => void;
  /** 解压成功回调，携带最终目标目录 id（0=根目录），便于跳转 */
  onExtracted?: (folderId: string) => void;
}

/** 压缩包名（去后缀）作为默认解压子目录名 */
function archiveBaseName(name: string): string {
  return name.replace(/\.[^.]+$/, '') || '解压目录';
}

/** 父目录路径（/a/b.zip -> /a；/x.zip -> /） */
function parentPathOf(path: string): string {
  const p = (path || '/').replace(/\/+$/, '');
  const idx = p.lastIndexOf('/');
  if (idx <= 0) return '/';
  return p.slice(0, idx);
}

/** 规范化路径：统一 / 分隔，返回非空段列表 */
function normalizeSegments(path: string): string[] {
  return path.trim().replace(/\\/g, '/').split('/').filter(Boolean);
}

/**
 * 在线解压对话框：浏览压缩包内容，可编辑目标路径（默认「所在文件夹/压缩包名」，
 * 缺失的目录解压时自动创建），解压过程显示进度条。
 */
export default function ArchiveDialog({ file, onClose, onExtracted }: Props) {
  const { showToast } = useToast();
  const { prompt } = usePrompt();
  const [entries, setEntries] = useState<ArchiveEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [extracting, setExtracting] = useState(false);
  const [creatingFolder, setCreatingFolder] = useState(false);

  const baseName = archiveBaseName(file.name);
  // 目标路径：默认「压缩包所在文件夹/压缩包名」，可手动编辑；目录树选择会同步更新
  const [targetFolderId, setTargetFolderId] = useState(file.parentId || '0');
  const [targetPath, setTargetPath] = useState(() => {
    const dir = parentPathOf(file.path || '');
    return dir === '/' ? `/${baseName}` : `${dir}/${baseName}`;
  });
  const [pathHint, setPathHint] = useState('');
  const [tree, setTree] = useState<FileTreeNode[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['0']));
  const [pickerOpen, setPickerOpen] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 目录树 id -> 路径 映射（根目录 '/'; 用于树选择时拼出完整目标路径）
  const [folderPathMap, setFolderPathMap] = useState<Map<string, string>>(new Map());

  useEffect(() => {
    api.get<ArchiveEntry[]>(`/file/${file.id}/archive/contents`)
      .then((data) => setEntries(data || []))
      .catch(() => showToast('读取压缩包失败', 'error'))
      .finally(() => setLoading(false));
  }, [file.id, showToast]);

  // 加载目录树（个人文件源，解压仅个人文件可用），并建立 id -> 路径 映射
  const loadTree = useCallback(async () => {
    try {
      const nodes = await api.get<FileTreeNode[]>('/file/tree');
      const list = nodes || [];
      setTree(list);
      const map = new Map<string, string>();
      const walk = (items: FileTreeNode[], prefix: string) => {
        for (const n of items) {
          const p = prefix === '/' ? '/' + n.name : prefix + '/' + n.name;
          map.set(String(n.id), p);
          if (n.children?.length) walk(n.children, p);
        }
      };
      walk(list, '/');
      setFolderPathMap(map);
    } catch {
      // 目录树加载失败不阻断解压（路径可直接输入）
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  // 卸载时清理轮询定时器
  useEffect(() => () => {
    if (pollTimerRef.current) clearTimeout(pollTimerRef.current);
  }, []);

  /** 按路径解析已有文件夹（不存在返回 null） */
  const resolveFolder = async (path: string): Promise<FileNode | null> => {
    try {
      const node = await api.get<FileNode>('/file/by-path', { params: { path } });
      return node || null;
    } catch {
      return null;
    }
  };

  /** 确保路径存在：逐级解析，缺失的目录自动创建，返回最终目录 id */
  const ensureFolderPath = async (path: string): Promise<string> => {
    let currentId = '0';
    let acc = '/';
    for (const seg of normalizeSegments(path)) {
      acc = acc === '/' ? '/' + seg : acc + '/' + seg;
      const node = await resolveFolder(acc);
      if (node) {
        if (node.nodeType !== 0) throw new Error(`路径 ${acc} 不是文件夹`);
        currentId = String(node.id);
        continue;
      }
      const created = await api.post<{ id?: number | string }>('/file/folder', { parentId: currentId, folderName: seg });
      const newId = created?.id != null ? String(created.id) : '';
      if (!newId) throw new Error(`创建目录 ${acc} 失败`);
      currentId = newId;
    }
    return currentId;
  };

  /** 轮询解压进度直到完成/失败，返回解压文件数 */
  const pollProgress = (taskId: string): Promise<number> =>
    new Promise((resolve, reject) => {
      const poll = async () => {
        try {
          const p = await api.get<{ status: string; total: number; done: number; error?: string; count?: number }>(
            `/file/${file.id}/archive/progress/${taskId}`,
          );
          setProgress({ done: p.done || 0, total: p.total || 0 });
          if (p.status === 'finished') {
            resolve(p.count ?? p.done ?? 0);
          } else if (p.status === 'failed') {
            reject(new Error(p.error || '解压失败'));
          } else if (p.status === 'missing') {
            reject(new Error('解压任务不存在，请重试'));
          } else {
            pollTimerRef.current = setTimeout(poll, 500);
          }
        } catch (e) {
          reject(e instanceof Error ? e : new Error('查询解压进度失败'));
        }
      };
      poll();
    });

  const handleExtract = async () => {
    if (extracting || entries.length === 0) return;
    if (normalizeSegments(targetPath).length === 0) {
      showToast('请输入解压目标路径', 'warning');
      return;
    }
    setExtracting(true);
    setProgress(null);
    try {
      const resolvedId = await ensureFolderPath(targetPath);
      const start = await api.post<{ taskId?: string }>(`/file/${file.id}/archive/extract`, null, {
        params: { targetFolderId: resolvedId },
      });
      if (!start?.taskId) throw new Error('解压任务创建失败');
      const count = await pollProgress(start.taskId);
      showToast(`成功解压 ${count} 个文件`, 'success');
      onExtracted?.(resolvedId);
      onClose();
    } catch (e) {
      showToast(e instanceof Error ? e.message : '解压失败', 'error');
    } finally {
      setExtracting(false);
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    }
  };

  /** 失焦/回车时校验路径：已存在则记录目录 id，不存在则提示将自动创建 */
  const handlePathBlur = async () => {
    const segs = normalizeSegments(targetPath);
    if (segs.length === 0) return;
    const full = '/' + segs.join('/');
    const node = await resolveFolder(full);
    if (node && node.nodeType !== 0) {
      setPathHint('该路径指向文件，无法作为解压目录');
    } else if (node) {
      setTargetFolderId(String(node.id));
      setPathHint('');
    } else {
      setTargetFolderId('');
      setPathHint('路径不存在，解压时将自动创建');
    }
  };

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectFolder = (id: string) => {
    const dir = folderPathMap.get(id) ?? '/';
    setTargetFolderId(id);
    setTargetPath(dir === '/' ? `/${baseName}` : `${dir}/${baseName}`);
    setPathHint('');
  };

  /** 在所选目录下新建文件夹：创建后刷新目录树并选中新目录 */
  const handleCreateFolder = async () => {
    if (creatingFolder) return;
    const name = await prompt({
      title: '新建文件夹',
      message: '在所选目录下新建文件夹',
      placeholder: '文件夹名称',
      confirmText: '创建',
    });
    if (!name) return;
    // 新建位置：当前选中的目录；手动输入未解析路径时回退根目录
    const parentId = targetFolderId || '0';
    setCreatingFolder(true);
    try {
      const created = await api.post<{ id?: number | string }>('/file/folder', { parentId, folderName: name });
      const newId = created?.id != null ? String(created.id) : '';
      if (!newId) throw new Error('新建文件夹失败');
      await loadTree();
      // 选中新文件夹并同步路径：父路径/新文件夹名/压缩包名
      const parentDir = parentId === '0' ? '/' : (folderPathMap.get(parentId) ?? '/');
      setTargetFolderId(newId);
      setTargetPath(parentDir === '/' ? `/${name}/${baseName}` : `${parentDir}/${name}/${baseName}`);
      setPathHint('');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '新建文件夹失败', 'error');
    } finally {
      setCreatingFolder(false);
    }
  };

  const renderTree = (nodes: FileTreeNode[], level: number): React.ReactNode => {
    return nodes.map((node) => (
      <div key={node.id}>
        <div
          className={cn(
            'flex items-center gap-1.5 px-2 py-1.5 rounded-lg cursor-pointer transition-colors text-sm',
            targetFolderId === String(node.id) ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted',
          )}
          style={{ paddingLeft: `${level * 20 + 8}px` }}
          onClick={() => selectFolder(String(node.id))}
        >
          {node.children.length > 0 ? (
            <button
              onClick={(e) => { e.stopPropagation(); toggleExpand(String(node.id)); }}
              className="p-0.5 hover:bg-surface-2 rounded cursor-pointer"
              aria-label="展开或折叠"
            >
              <ChevronRight className={cn('w-3.5 h-3.5 transition-transform', expanded.has(String(node.id)) && 'rotate-90')} aria-hidden />
            </button>
          ) : (
            <span className="w-4" />
          )}
          {targetFolderId === String(node.id) ? (
            <FolderOpen className="w-4 h-4 text-primary-600 flex-shrink-0" />
          ) : (
            <FolderClosed className="w-4 h-4 text-amber-500 flex-shrink-0" />
          )}
          <span className="truncate">{node.name}</span>
        </div>
        {expanded.has(String(node.id)) && node.children.length > 0 && renderTree(node.children, level + 1)}
      </div>
    ));
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={extracting ? undefined : onClose}>
      <div
        className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in flex flex-col max-h-[80vh]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-border flex-shrink-0">
          <div className="flex items-center gap-2 min-w-0">
            <Archive className="w-5 h-5 text-primary-600 flex-shrink-0" />
            <h2 className="text-base font-semibold text-fg truncate">{file.name}</h2>
          </div>
          <button
            onClick={onClose}
            disabled={extracting}
            className="text-muted hover:text-fg flex-shrink-0 disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label="关闭"
          >
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        <div className="flex-1 min-h-[200px] p-4 flex flex-col overflow-hidden">
          {pickerOpen ? (
            /* 目录选择模式：目录树占满弹窗主体 */
            <div className="flex flex-col h-full min-h-0">
              <div className="flex items-center justify-between mb-2 flex-shrink-0">
                <span className="text-xs font-medium text-muted">选择目标目录（将自动拼上压缩包名）</span>
                <button
                  type="button"
                  onClick={handleCreateFolder}
                  disabled={extracting || creatingFolder}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-primary-600 bg-primary-500/10 rounded-lg hover:bg-primary-500/15 transition-colors cursor-pointer disabled:opacity-50"
                >
                  {creatingFolder ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden />
                  ) : (
                    <FolderPlus className="w-3.5 h-3.5" aria-hidden />
                  )}
                  新建文件夹
                </button>
              </div>
              <div className="flex-1 min-h-0 overflow-y-auto rounded-lg border border-border p-1.5">
                {/* 根目录选项 */}
                <div
                  className={cn(
                    'flex items-center gap-2 px-2 py-1.5 rounded-lg cursor-pointer transition-colors text-sm',
                    targetFolderId === '0' ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted',
                  )}
                  onClick={() => selectFolder('0')}
                >
                  {targetFolderId === '0' ? (
                    <FolderOpen className="w-4 h-4 text-primary-600 flex-shrink-0" aria-hidden />
                  ) : (
                    <FolderClosed className="w-4 h-4 text-amber-500 flex-shrink-0" aria-hidden />
                  )}
                  <span>根目录</span>
                </div>
                {renderTree(tree, 0)}
              </div>
            </div>
          ) : loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="w-8 h-8 border-2 border-primary-200 border-t-primary-600 rounded-full animate-spin" />
            </div>
          ) : entries.length === 0 ? (
            <div className="text-center text-sm text-muted py-12">压缩包为空</div>
          ) : (
            <div className="flex-1 min-h-0 overflow-y-auto space-y-0.5">
              {entries.map((entry, idx) => (
                <div
                  key={idx}
                  className="flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-surface-2 text-sm"
                >
                  {entry.isDirectory ? (
                    <Folder className="w-4 h-4 text-amber-500 flex-shrink-0" />
                  ) : (
                    <File className="w-4 h-4 text-muted flex-shrink-0" />
                  )}
                  <span className={cn('flex-1 truncate', entry.isDirectory ? 'text-fg font-medium' : 'text-muted')}>
                    {entry.fileName}
                  </span>
                  {!entry.isDirectory && entry.size > 0 && (
                    <span className="text-xs text-muted tabular-nums flex-shrink-0">{formatSize(entry.size)}</span>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="px-5 py-3 border-t border-border flex-shrink-0 space-y-3">
          {/* 目标路径：可手动编辑，目录树选择同步 */}
          <div>
            <label htmlFor="archive-target-path" className="block text-xs font-medium text-muted mb-1.5">
              解压到
            </label>
            <div className="flex gap-2">
              <div className="relative flex-1 min-w-0">
                <Folder className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-amber-500 flex-shrink-0" aria-hidden />
                <input
                  id="archive-target-path"
                  type="text"
                  value={targetPath}
                  onChange={(e) => { setTargetPath(e.target.value); setPathHint(''); }}
                  onBlur={handlePathBlur}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
                  disabled={extracting}
                  className="input-field pl-9 font-mono text-xs disabled:opacity-60"
                  placeholder="例如 /我的文档/解压文件"
                  spellCheck={false}
                />
              </div>
              <button
                type="button"
                onClick={() => setPickerOpen(!pickerOpen)}
                aria-expanded={pickerOpen}
                disabled={extracting}
                className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-muted bg-surface-2 rounded-lg border border-border hover:bg-surface-2 hover:text-fg transition-colors cursor-pointer flex-shrink-0"
              >
                <FolderOpen className="w-3.5 h-3.5" aria-hidden />
                选择目录
                <ChevronDown className={cn('w-3.5 h-3.5 transition-transform', pickerOpen && 'rotate-180')} aria-hidden />
              </button>
            </div>
            {pathHint && <p className="mt-1.5 text-xs text-amber-600 dark:text-amber-400">{pathHint}</p>}
          </div>

          {/* 解压进度条：按「已创建文件数 / 压缩包文件总数」显示真实百分比 */}
          {extracting && (
            <div
              className="space-y-1.5"
              role="progressbar"
              aria-label="解压进度"
              aria-valuemin={0}
              aria-valuemax={progress?.total || 100}
              aria-valuenow={progress?.done || 0}
            >
              <div className="flex items-center justify-between gap-2 text-xs text-muted">
                <span className="flex items-center gap-2">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden />
                  正在解压，请稍候…
                </span>
                {progress && progress.total > 0 && (
                  <span className="tabular-nums">
                    {Math.min(100, Math.round((progress.done / progress.total) * 100))}%（{progress.done}/{progress.total}）
                  </span>
                )}
              </div>
              <div className="h-1.5 bg-surface-2 rounded-full overflow-hidden">
                {progress && progress.total > 0 ? (
                  <div
                    className="h-full bg-primary-600 rounded-full transition-[width] duration-300"
                    style={{ width: `${Math.min(100, Math.round((progress.done / progress.total) * 100))}%` }}
                  />
                ) : (
                  <div className="h-full w-1/2 bg-primary-600 rounded-full animate-progress-indeterminate" />
                )}
              </div>
            </div>
          )}

          <div className="flex items-center justify-between">
            <span className="text-xs text-muted">{entries.length} 个条目</span>
            <button
              onClick={handleExtract}
              disabled={extracting || entries.length === 0}
              className="flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50"
            >
              <Download className="w-4 h-4" aria-hidden />
              {extracting ? '解压中…' : '开始解压'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
