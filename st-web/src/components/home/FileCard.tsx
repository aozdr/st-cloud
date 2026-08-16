import type { ReactNode } from 'react';
import type { FileNode } from '../../types';
import { getFileTypeConfig } from '../../lib/utils';
import FileTypeIcon from '../file/FileTypeIcon';

interface FileCardProps {
  file: FileNode;
  /** 图标自定义（如缩略图）；缺省按文件类型渲染图标 */
  icon?: ReactNode;
  subtitle?: ReactNode;
  trailing?: ReactNode;
  /** aria-label 前缀（收藏/访问/文件） */
  actionLabel: string;
  onOpen: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
}

/** 首页文件卡片：收藏/最近访问/最近文件共用 */
export default function FileCard({ file, icon, subtitle, trailing, actionLabel, onOpen, onContextMenu }: FileCardProps) {
  const config = getFileTypeConfig(file.nodeType, file.suffix);
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={`${actionLabel}：${file.name}`}
      onClick={onOpen}
      onContextMenu={onContextMenu}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
      className="group flex items-center gap-3 p-3 bg-surface rounded-xl hover:shadow-card hover:-translate-y-0.5 transition-[transform,box-shadow] duration-200 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg"
    >
      <div className="flex-shrink-0">
        {icon ?? <FileTypeIcon config={config} size="sm" isFolder={file.nodeType === 0} suffix={file.suffix} />}
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium text-fg truncate">{file.name}</div>
        {subtitle && <div className="text-xs text-muted mt-0.5 truncate">{subtitle}</div>}
      </div>
      {trailing}
    </div>
  );
}
