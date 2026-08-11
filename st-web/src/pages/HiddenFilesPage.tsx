import { useState, useEffect } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import api from '../lib/api';
import EmptyState from '../components/EmptyState';
import FileTypeIcon from '../components/file/FileTypeIcon';
import { getFileTypeConfig, formatSize, formatDate } from '../lib/utils';
import { useToast } from '../components/ui/Toast';
import type { FileNode } from '../types';

/**
 * 隐藏文件页面：展示被标记为隐藏的文件/文件夹，支持取消隐藏
 */
export default function HiddenFilesPage() {
  const { showToast } = useToast();
  const [files, setFiles] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchHidden = () => {
    api.get<FileNode[]>('/file/hidden')
      .then(setFiles)
      .catch(() => showToast('加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(fetchHidden, []);

  const handleUnhide = async (file: FileNode) => {
    await api.put(`/file/${file.id}/unhide`);
    showToast('已取消隐藏', 'success');
    fetchHidden();
  };

  if (loading) {
    return <div className="flex items-center justify-center h-full"><div className="w-8 h-8 border-2 border-primary-200 border-t-primary-600 rounded-full animate-spin" /></div>;
  }

  if (files.length === 0) {
    return <div className="h-full flex items-center justify-center"><EmptyState type="generic" title="无隐藏文件" description="右键文件可选择「隐藏」将其从此处管理" /></div>;
  }

  return (
    <div className="h-full overflow-auto">
      <div className="px-5 py-3 border-b border-border flex items-center gap-2">
        <EyeOff className="w-4 h-4 text-muted" />
        <h2 className="text-base font-semibold text-fg">隐藏文件</h2>
        <span className="text-xs text-muted">{files.length} 项</span>
      </div>
      <div className="p-4 space-y-1">
        {files.map((file) => {
          const config = getFileTypeConfig(file.nodeType, file.suffix);
          return (
            <div key={file.id} className="group flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-surface-2 transition-colors">
              <FileTypeIcon config={config} size="md" isFolder={file.nodeType === 0} suffix={file.suffix} />
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-fg truncate">{file.name}</div>
                <div className="text-xs text-muted">{file.nodeType === 0 ? '文件夹' : `${config.label} · ${formatSize(file.fileSize)} · ${formatDate(file.updatedAt)}`}</div>
              </div>
              <button
                onClick={() => handleUnhide(file)}
                className="flex items-center gap-1 px-3 py-1.5 text-xs text-primary-600 hover:bg-primary-500/10 rounded-lg cursor-pointer opacity-0 group-hover:opacity-100 transition-opacity"
              >
                <Eye className="w-3.5 h-3.5" />
                取消隐藏
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
