import { useState, useEffect, useCallback } from 'react';
import { Copy, ChevronDown, ChevronRight, AlertTriangle, Trash2, History, RefreshCw, FileText } from 'lucide-react';
import api from '../lib/api';
import EmptyState from '../components/EmptyState';
import { useToast } from '../components/ui/Toast';
import { formatSize, formatDate, cn } from '../lib/utils';
import type { FileNode } from '../types';

interface DuplicateGroup {
  fileMd5: string;
  cnt: number;
  totalSize: number;
  sampleName: string;
  sampleId: number;
}

interface CleanupResult {
  total: number;
  deletedCount: number;
  skippedCount: number;
  keptId?: number;
  keptName?: string;
}

/**
 * 重复文件检测页面：
 * - 按 MD5 分组展示重复文件
 * - 展开后显示文件详情列表
 * - 支持单组清理：保留创建时间最早的，其余移入回收站（有历史版本的文件跳过）
 */
export default function DuplicateFilesPage() {
  const { showToast } = useToast();
  const [groups, setGroups] = useState<DuplicateGroup[]>([]);
  const [expandedMd5, setExpandedMd5] = useState<string | null>(null);
  const [groupFiles, setGroupFiles] = useState<Record<string, FileNode[]>>({});
  const [versionMap, setVersionMap] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [cleaning, setCleaning] = useState<string | null>(null);

  const fetchDuplicates = useCallback(() => {
    setLoading(true);
    api.get<DuplicateGroup[]>('/file/duplicates')
      .then((data) => setGroups((data || []).filter((g: any) => g && g.fileMd5)))
      .catch(() => showToast('检测失败', 'error'))
      .finally(() => setLoading(false));
  }, [showToast]);

  useEffect(() => { fetchDuplicates(); }, [fetchDuplicates]);

  const totalWasted = groups.reduce((sum, g) => sum + ((g.totalSize || 0) * ((g.cnt || 0) - 1)), 0);

  /** 展开组：加载该 MD5 的文件列表，并逐个检查是否有历史版本 */
  const toggleGroup = async (md5: string) => {
    if (expandedMd5 === md5) { setExpandedMd5(null); return; }
    setExpandedMd5(md5);
    if (!groupFiles[md5]) {
      try {
        const files = await api.get<FileNode[]>('/file/duplicates/detail', { params: { md5 } });
        setGroupFiles((prev) => ({ ...prev, [md5]: files || [] }));
        // 逐个检查是否有历史版本
        for (const f of files || []) {
          try {
            const count = await api.get<number>(`/file/${f.id}/versions/count`);
            setVersionMap((prev) => ({ ...prev, [f.id]: count > 0 }));
          } catch {
            setVersionMap((prev) => ({ ...prev, [f.id]: false }));
          }
        }
      } catch {
        showToast('加载文件列表失败', 'error');
      }
    }
  };

  /** 清理冗余副本：保留创建时间最早的，其余移入回收站 */
  const handleCleanup = async (md5: string) => {
    setCleaning(md5);
    try {
      const result = await api.post<CleanupResult>('/file/duplicates/cleanup', null, { params: { md5 } });
      const msg = result.skippedCount > 0
        ? `已清理 ${result.deletedCount} 个冗余文件，${result.skippedCount} 个有历史版本已跳过`
        : `已清理 ${result.deletedCount} 个冗余文件`;
      showToast(msg, 'success');
      // 清理后刷新数据
      setGroupFiles({});
      setVersionMap({});
      setExpandedMd5(null);
      fetchDuplicates();
    } catch {
      showToast('清理失败', 'error');
    } finally {
      setCleaning(null);
    }
  };

  return (
    <div className="h-full overflow-auto">
      {/* 页头 */}
      <div className="sticky top-0 z-10 flex items-center justify-between px-5 py-3 bg-surface/80 backdrop-blur-md border-b border-border">
        <div className="flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 text-amber-500" />
          <h2 className="text-base font-semibold text-fg">重复文件检测</h2>
          {!loading && groups.length > 0 && (
            <span className="text-xs text-muted">{groups.length} 组 · 可释放 {formatSize(totalWasted)}</span>
          )}
        </div>
        <button onClick={fetchDuplicates} className="btn-ghost" title="重新检测">
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <div className="w-8 h-8 border-2 border-primary-200 border-t-primary-600 rounded-full animate-spin" />
        </div>
      ) : groups.length === 0 ? (
        <EmptyState type="search" title="未发现重复文件" description="你的文件存储很整洁" />
      ) : (
        <div className="p-4 space-y-2 max-w-3xl mx-auto">
          {groups.map((group) => {
            const files = groupFiles[group.fileMd5] || [];
            const allHaveVersions = files.length > 0 && files.every((f) => versionMap[f.id]);
            const isExpanded = expandedMd5 === group.fileMd5;
            const isCleaning = cleaning === group.fileMd5;

            return (
              <div key={group.fileMd5} className="bg-surface rounded-lg border border-border overflow-hidden">
                {/* 组标题行 */}
                <button
                  onClick={() => toggleGroup(group.fileMd5)}
                  className="w-full flex items-center gap-2 px-4 py-3 hover:bg-surface-2 transition-colors cursor-pointer text-left"
                >
                  {isExpanded
                    ? <ChevronDown className="w-4 h-4 text-muted flex-shrink-0" />
                    : <ChevronRight className="w-4 h-4 text-muted flex-shrink-0" />}
                  <Copy className="w-4 h-4 text-primary-500 flex-shrink-0" />
                  <span className="text-sm font-medium text-fg flex-1 truncate">{group.sampleName}</span>
                  <span className="text-xs text-muted flex-shrink-0">{group.cnt} 份</span>
                  <span className="text-xs text-amber-600 flex-shrink-0">浪费 {formatSize((group.totalSize || 0) * ((group.cnt || 0) - 1))}</span>
                </button>

                {/* 展开内容 */}
                {isExpanded && (
                  <div className="border-t border-border">
                    {/* 文件列表 */}
                    <div className="divide-y divide-border/60">
                      {files.length === 0 ? (
                        <div className="px-4 py-3 text-xs text-muted">加载中...</div>
                      ) : files.map((file, idx) => {
                        const hasVersions = versionMap[file.id];
                        const isKept = idx === 0;
                        return (
                          <div key={file.id} className="flex items-center gap-2 px-4 py-2 text-xs">
                            <FileText className="w-3.5 h-3.5 text-muted flex-shrink-0" />
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-1.5">
                                <span className={cn('truncate', isKept ? 'text-emerald-600 font-medium' : 'text-fg')}>{file.name}</span>
                                {isKept && <span className="text-[10px] text-emerald-600 bg-emerald-500/10 px-1 rounded">保留</span>}
                                {hasVersions && (
                                  <span className="flex items-center gap-0.5 text-[10px] text-amber-600 bg-amber-500/10 px-1 rounded" title="有历史版本，不可删除">
                                    <History className="w-2.5 h-2.5" />
                                    有版本
                                  </span>
                                )}
                              </div>
                              <div className="text-muted truncate mt-0.5">{file.path || '/'}</div>
                            </div>
                            <span className="text-muted tabular-nums flex-shrink-0">{formatDate(file.createdAt)}</span>
                          </div>
                        );
                      })}
                    </div>

                    {/* 清理按钮 */}
                    {files.length > 0 && (
                      <div className="flex items-center justify-between px-4 py-2.5 bg-surface-2/50">
                        <span className="text-xs text-muted">
                          {allHaveVersions
                            ? '所有文件均有历史版本，无法清理'
                            : '保留创建时间最早的文件，其余移入回收站'}
                        </span>
                        <button
                          onClick={() => handleCleanup(group.fileMd5)}
                          disabled={isCleaning || allHaveVersions}
                          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 hover:bg-red-500/10 rounded-lg cursor-pointer transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                          {isCleaning ? '清理中...' : '清理冗余副本'}
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
