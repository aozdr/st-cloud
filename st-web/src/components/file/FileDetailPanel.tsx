import type { FileNode } from '../../types';
import { getFileTypeConfig, formatSize, formatDate } from '../../lib/utils';
import FileThumbnail from './FileThumbnail';
import { X, FolderOpen, Clock, HardDrive, Calendar, Hash } from 'lucide-react';

interface Props {
  file: FileNode;
  onClose: () => void;
}

export default function FileDetailPanel({ file, onClose }: Props) {
  const config = getFileTypeConfig(file.nodeType, file.suffix);

  return (
    <aside className="w-80 flex-shrink-0 border-l border-border bg-surface overflow-y-auto flex flex-col animate-slide-up">
      <div className="flex items-center justify-between px-4 py-3 border-b border-border">
        <span className="text-sm font-semibold text-fg">详情</span>
        <button
          onClick={onClose}
          aria-label="关闭详情面板"
          className="text-muted hover:text-fg cursor-pointer p-0.5 rounded hover:bg-surface-2 transition-colors"
        >
          <X className="w-4 h-4" aria-hidden />
        </button>
      </div>

      <div className="flex flex-col items-center px-4 py-6 border-b border-border">
        <div className="w-28 h-28 rounded-2xl bg-surface-2 flex items-center justify-center mb-4 overflow-hidden">
          <FileThumbnail file={file} size="xxl" />
        </div>
        <span className="text-sm font-medium text-fg text-center break-all line-clamp-3">{file.name}</span>
        <span className="mt-1 text-xs text-muted">{file.nodeType === 0 ? '文件夹' : config.label}</span>
      </div>

      <div className="flex-1 px-4 py-4 space-y-3">
        {file.nodeType === 1 && (
          <DetailRow icon={HardDrive} label="大小" value={formatSize(file.fileSize)} />
        )}
        {file.path && file.path !== '/' && (
          <DetailRow icon={FolderOpen} label="位置" value={file.path} />
        )}
        {file.suffix && (
          <DetailRow icon={Hash} label="扩展名" value={`.${file.suffix}`} />
        )}
        <DetailRow icon={Calendar} label="创建时间" value={formatDate(file.createdAt)} />
        <DetailRow icon={Clock} label="修改时间" value={formatDate(file.updatedAt)} />
      </div>
    </aside>
  );
}

function DetailRow({ icon: Icon, label, value }: { icon: typeof Clock; label: string; value: string }) {
  return (
    <div className="flex items-start gap-3 rounded-lg px-1 py-1 hover:bg-surface-2/60 transition-colors">
      <Icon className="w-4 h-4 text-muted flex-shrink-0 mt-0.5" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="text-xs text-muted mb-0.5">{label}</div>
        <div className="text-sm text-fg break-all">{value || '-'}</div>
      </div>
    </div>
  );
}