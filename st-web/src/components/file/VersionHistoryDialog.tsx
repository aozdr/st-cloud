import { useState, useEffect, useRef } from 'react';
import { X, History, RotateCcw, Loader2, ShieldCheck, Upload } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { useUpload } from '../../hooks/useUpload';
import { isElectron } from '../../lib/electron';
import { formatSize, formatDate } from '../../lib/utils';
import type { FileVersionVO, FileNode } from '../../types';

interface Props {
  node: FileNode;
  onClose: () => void;
  onRestored: () => void;
}

export default function VersionHistoryDialog({ node, onClose, onRestored }: Props) {
  const [versions, setVersions] = useState<FileVersionVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [restoringId, setRestoringId] = useState<string | null>(null);
  const { showToast } = useToast();
  const { addFiles, addFilePaths } = useUpload();
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    const fetchVersions = async () => {
      setLoading(true);
      try {
        const data: FileVersionVO[] = await api.get(`/file/${node.id}/versions`);
        if (!cancelled) setVersions(data || []);
      } catch {
        if (!cancelled) showToast('获取版本历史失败', 'error');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchVersions();
    return () => { cancelled = true; };
  }, [node.id, showToast]);

  const handleRestore = async (versionId: string) => {
    setRestoringId(versionId);
    try {
      await api.post(`/file/${node.id}/versions/${versionId}/restore`);
      showToast('已恢复到该版本', 'success');
      onRestored();
      onClose();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '恢复失败', 'error');
    } finally {
      setRestoringId(null);
    }
  };

  const handleUploadNewVersion = async () => {
    if (isElectron()) {
      const filePaths = await window.electronAPI!.selectFiles();
      if (filePaths.length === 0) return;
      addFilePaths(filePaths, node.parentId, node.id);
    } else {
      fileInputRef.current?.click();
    }
    showToast('新版本上传已加入队列', 'success');
    onClose();
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (!file) return;
    addFiles([file], node.parentId, node.id);
    showToast('新版本上传已加入队列', 'success');
    onClose();
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-content w-[520px] max-w-[92vw] p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <input ref={fileInputRef} type="file" className="hidden" onChange={handleFileSelect} />

        <div className="flex items-center justify-between mb-1">
          <h2 className="text-lg font-semibold text-fg flex items-center gap-2">
            <History className="w-5 h-5 text-primary-600" />
            历史版本
          </h2>
          <div className="flex items-center gap-2">
            <button
              onClick={handleUploadNewVersion}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-muted bg-surface border border-border rounded-md hover:bg-surface-2 hover:border-border transition-colors cursor-pointer"
            >
              <Upload className="w-3.5 h-3.5" />
              上传新版本
            </button>
            <button
              onClick={onClose}
              aria-label="关闭" className="p-1 text-muted hover:text-fg rounded-md hover:bg-surface-2 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <X className="w-5 h-5" aria-hidden />
            </button>
          </div>
        </div>
        <p className="text-sm text-muted mb-4 truncate">{node.name}</p>

        {loading ? (
          <div className="flex items-center justify-center py-12 text-muted">
            <Loader2 className="w-6 h-6 animate-spin" />
          </div>
        ) : versions.length === 0 ? (
          <div className="text-center py-12 text-muted text-sm">暂无历史版本</div>
        ) : (
          <div className="space-y-2 max-h-[50vh] overflow-y-auto -mr-2 pr-2">
            {versions.map((v) => {
              const isCurrent = v.current;
              const isRestoring = restoringId === v.id;
              return (
                <div
                  key={v.id}
                  className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                    isCurrent ? 'border-primary-200 bg-primary-50/50' : 'border-border hover:bg-surface-2'
                  }`}
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-sm font-medium text-fg">V{v.versionNum}</span>
                      {isCurrent && (
                        <span className="inline-flex items-center gap-1 text-xs text-primary-600 bg-primary-100 px-1.5 py-0.5 rounded">
                          <ShieldCheck className="w-3 h-3" />
                          当前版本
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-muted flex items-center gap-3 flex-wrap">
                      <span>{formatSize(v.fileSize)}</span>
                      <span>{v.modifierName || '未知'}</span>
                      <span>{formatDate(v.createdAt)}</span>
                    </div>
                  </div>
                  <button
                    onClick={() => handleRestore(v.id)}
                    disabled={isCurrent || isRestoring}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-muted bg-surface border border-border rounded-md hover:bg-surface-2 hover:border-border transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0"
                  >
                    {isRestoring ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    ) : (
                      <RotateCcw className="w-3.5 h-3.5" />
                    )}
                    恢复
                  </button>
                </div>
              );
            })}
          </div>
        )}

        <div className="mt-5 flex justify-end">
          <button onClick={onClose} className="btn-secondary">关闭</button>
        </div>
      </div>
    </div>
  );
}