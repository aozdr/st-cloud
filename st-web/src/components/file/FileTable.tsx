import { getFileTypeConfig, formatSize, formatDate, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import { Check, MoreHorizontal } from 'lucide-react';
import FileThumbnail from './FileThumbnail';

interface Props {
  files: FileNode[];
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onSelectAll: () => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
  onItemDragStart?: (e: React.DragEvent, node: FileNode) => void;
  onFolderDragOver?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDragLeave?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDrop?: (e: React.DragEvent, folder: FileNode) => void;
  dragOverFolderId?: string | null;
  onItemMenu?: (e: React.MouseEvent, node: FileNode) => void;
}

export default function FileTable({ files, selectedIds, focusedId, cutIds, onSelect, onSelectAll, onContextMenu, onDoubleClick, onItemDragStart, onFolderDragOver, onFolderDragLeave, onFolderDrop, dragOverFolderId, onItemMenu }: Props) {
  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const someSelected = files.some((f) => selectedIds.has(f.id));
  const selectedCount = files.filter((f) => selectedIds.has(f.id)).length;

  return (
    <div className="rounded-xl bg-white overflow-hidden">
      <div className="flex items-center gap-3 px-4 h-10 border-b border-stone-100">
        <button
          onClick={onSelectAll}
          aria-label="全选"
          className={cn(
            'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center cursor-pointer transition-colors flex-shrink-0',
            allSelected
              ? 'bg-primary-600 border-primary-600'
              : someSelected
                ? 'bg-primary-100 border-primary-400'
                : 'border-stone-300 hover:border-primary-500'
          )}
        >
          {allSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
          {someSelected && !allSelected && <div className="w-2 h-0.5 bg-primary-600 rounded" />}
        </button>
        <span className="text-xs text-stone-400 select-none">
          {someSelected ? `已选 ${selectedCount} 项` : `${files.length} 个项目`}
        </span>
      </div>

      <div className="divide-y divide-stone-50">
        {files.map((file, index) => {
          const config = getFileTypeConfig(file.nodeType, file.suffix);
          const isSelected = selectedIds.has(file.id);

          return (
            <div
              key={file.id}
              data-file-id={file.id}
              draggable
              onDragStart={(e) => onItemDragStart?.(e, file)}
              onDragOver={file.nodeType === 0 ? (e) => onFolderDragOver?.(e, file) : undefined}
              onDragLeave={file.nodeType === 0 ? (e) => onFolderDragLeave?.(e, file) : undefined}
              onDrop={file.nodeType === 0 ? (e) => onFolderDrop?.(e, file) : undefined}
              onClick={(e) => onSelect(file.id, e)}
              onDoubleClick={() => onDoubleClick(file)}
              onContextMenu={(e) => onContextMenu(e, file)}
              style={{ animationDelay: `${Math.min(index, 20) * 24}ms` }}
              className={cn(
                'group relative flex items-center gap-3 px-4 h-[52px] cursor-pointer transition-colors duration-150 animate-file-enter',
                isSelected
                  ? 'bg-primary-50/70'
                  : dragOverFolderId === file.id
                    ? 'bg-primary-50 ring-2 ring-primary-400 ring-inset'
                    : 'hover:bg-stone-50/70',
                focusedId === file.id && !isSelected && dragOverFolderId !== file.id && 'bg-primary-50/30',
                cutIds?.has(file.id) && 'opacity-50'
              )}
            >
              {isSelected && <span className="absolute left-0 top-2 bottom-2 w-[3px] rounded-r bg-primary-600" />}

              <button
                onClick={(e) => { e.stopPropagation(); onSelect(file.id, e); }}
                aria-label="选择"
                className={cn(
                  'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center transition-all flex-shrink-0',
                  isSelected ? 'bg-primary-600 border-primary-600 opacity-100' : 'border-stone-300 bg-white opacity-0 group-hover:opacity-100'
                )}
              >
                {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
              </button>

              <FileThumbnail file={file} size="lg" />

              <div className="flex-1 min-w-0">
                <div className={cn('text-sm truncate', isSelected ? 'text-primary-700 font-medium' : 'text-stone-700')}>
                  {file.name}
                </div>
                <div className="text-xs text-stone-400 truncate flex items-center gap-1.5">
                  <span>{file.nodeType === 0 ? '文件夹' : config.label}</span>
                  <span className="text-stone-300">·</span>
                  <span className="tabular-nums">{file.nodeType === 0 ? '-' : formatSize(file.fileSize)}</span>
                  <span className="text-stone-300">·</span>
                  <span className="tabular-nums">{formatDate(file.updatedAt)}</span>
                </div>
              </div>

              {onItemMenu && (
                <button
                  onClick={(e) => { e.stopPropagation(); onItemMenu(e, file); }}
                  onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); onContextMenu(e, file); }}
                  aria-label="更多操作"
                  className="flex-shrink-0 w-8 h-8 rounded-lg flex items-center justify-center text-stone-400 hover:text-stone-700 hover:bg-stone-100 opacity-0 group-hover:opacity-100 transition-all"
                >
                  <MoreHorizontal className="w-4 h-4" aria-hidden />
                </button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}