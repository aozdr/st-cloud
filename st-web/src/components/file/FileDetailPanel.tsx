import type { FileNode } from '../../types';
import { getFileTypeConfig, formatSize, formatDate } from '../../lib/utils';
import FileTypeIcon from './FileTypeIcon';
import { X, FolderOpen, Clock, HardDrive, Calendar, FileText } from 'lucide-react';

interface Props {
  file: FileNode;
  onClose: () => void;
}

export default function FileDetailPanel({ file, onClose }: Props) {
  const config = getFileTypeConfig(file.nodeType, file.suffix);

  return (
    <aside className="w-72 flex-shrink-0 border-l border-stone-200 bg-white overflow-y-auto flex flex-col animate-slide-up">
      <div className="flex items-center justify-between px-4 py-3 border-b border-stone-100">
        <span className="text-sm font-semibold text-stone-900">详情</span>
        <button onClick={onClose} aria-label="关闭详情面板" className="text-stone-400 hover:text-stone-600 cursor-pointer p-0.5 rounded hover:bg-stone-100 transition-colors">
          <X className="w-4 h-4" aria-hidden />
        </button>
      </div>

      <div className="flex flex-col items-center px-4 py-6 border-b border-stone-100">
        <div className="w-20 h-20 rounded-2xl bg-stone-50 flex items-center justify-center mb-3">
          <FileTypeIcon config={config} size="xl" isFolder={file.nodeType === 0} suffix={file.suffix} />
        </div>
        <span className="text-sm font-medium text-stone-900 text-center break-all line-clamp-3">{file.name}</span>
      </div>

      <div className="flex-1 px-4 py-4 space-y-4">
        <DetailRow icon={FileText} label="类型" value={file.nodeType === 0 ? '文件夹' : config.label} />
        {file.nodeType === 1 && (
          <DetailRow icon={HardDrive} label="大小" value={formatSize(file.fileSize)} />
        )}
        {file.path && file.path !== '/' && (
          <DetailRow icon={FolderOpen} label="位置" value={file.path} />
        )}
        <DetailRow icon={Calendar} label="创建时间" value={formatDate(file.createdAt)} />
        <DetailRow icon={Clock} label="修改时间" value={formatDate(file.updatedAt)} />
      </div>
    </aside>
  );
}

function DetailRow({ icon: Icon, label, value }: { icon: typeof Clock; label: string; value: string }) {
  return (
    <div className="flex items-start gap-2.5">
      <Icon className="w-4 h-4 text-stone-400 flex-shrink-0 mt-0.5" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="text-xs text-stone-400 mb-0.5">{label}</div>
        <div className="text-sm text-stone-700 break-all">{value || '-'}</div>
      </div>
    </div>
  );
}