import { useState, useEffect } from 'react';
import { X, File, Folder, Download, Archive } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { formatSize, cn } from '../../lib/utils';
import type { FileNode } from '../../types';

interface ArchiveEntry {
  name: string;
  fileName: string;
  size: number;
  isDirectory: boolean;
}

interface Props {
  file: FileNode;
  onClose: () => void;
  onExtracted?: () => void;
}

/**
 * 在线解压对话框：浏览 ZIP 压缩包内容，支持一键解压到当前目录
 */
export default function ArchiveDialog({ file, onClose, onExtracted }: Props) {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<ArchiveEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [extracting, setExtracting] = useState(false);

  useEffect(() => {
    api.get<ArchiveEntry[]>(`/file/${file.id}/archive/contents`)
      .then((data) => setEntries(data || []))
      .catch(() => showToast('读取压缩包失败', 'error'))
      .finally(() => setLoading(false));
  }, [file.id]);

  const handleExtract = async () => {
    setExtracting(true);
    try {
      const count = await api.post<number>(`/file/${file.id}/archive/extract`, null, {
        params: { targetFolderId: file.parentId || '0' },
      });
      showToast(`成功解压 ${count} 个文件`, 'success');
      onExtracted?.();
      onClose();
    } catch {
      showToast('解压失败', 'error');
    } finally {
      setExtracting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in flex flex-col max-h-[80vh]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-border flex-shrink-0">
          <div className="flex items-center gap-2 min-w-0">
            <Archive className="w-5 h-5 text-primary-600 flex-shrink-0" />
            <h2 className="text-base font-semibold text-fg truncate">{file.name}</h2>
          </div>
          <button onClick={onClose} className="text-muted hover:text-fg flex-shrink-0" aria-label="关闭">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-auto p-4 min-h-[200px]">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="w-8 h-8 border-2 border-primary-200 border-t-primary-600 rounded-full animate-spin" />
            </div>
          ) : entries.length === 0 ? (
            <div className="text-center text-sm text-muted py-12">压缩包为空</div>
          ) : (
            <div className="space-y-0.5">
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

        <div className="flex items-center justify-between px-5 py-3 border-t border-border flex-shrink-0">
          <span className="text-xs text-muted">{entries.length} 个条目</span>
          <button
            onClick={handleExtract}
            disabled={extracting || entries.length === 0}
            className="flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50"
          >
            <Download className="w-4 h-4" />
            {extracting ? '解压中...' : '解压到当前目录'}
          </button>
        </div>
      </div>
    </div>
  );
}
