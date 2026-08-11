import { formatSize, formatDate, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import type { SortBy, SortDir } from './FileToolbar';
import { Check, MoreHorizontal, ArrowUp, ArrowDown, Star } from 'lucide-react';
import FileThumbnail from './FileThumbnail';

interface Props {
  files: FileNode[];
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  sortBy: SortBy;
  sortDir: SortDir;
  onSortChange: (col: SortBy) => void;
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
  onToggleSelect: (id: string) => void;
  isFavorite: (id: string) => boolean;
  onToggleFavorite: (node: FileNode) => void;
}

export default function FileTableView({
  files, selectedIds, focusedId, cutIds, sortBy, sortDir, onSortChange,
  onSelect, onSelectAll, onContextMenu, onDoubleClick,
  onItemDragStart, onFolderDragOver, onFolderDragLeave, onFolderDrop, dragOverFolderId, onItemMenu,
  onToggleSelect, isFavorite, onToggleFavorite,
}: Props) {
  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const someSelected = files.some((f) => selectedIds.has(f.id));

  const renderSortHeader = (col: SortBy, label: string, align?: 'right') => (
    <th scope="col" className={cn('h-10 px-3 text-xs font-medium text-muted select-none', align === 'right' && 'text-right')}>
      <button
        type="button"
        onClick={() => onSortChange(col)}
        className={cn(
          'inline-flex items-center gap-1 transition-colors cursor-pointer hover:text-fg focus-visible:outline-none focus-visible:text-primary-600',
          align === 'right' && 'flex-row-reverse',
        )}
        aria-sort={sortBy === col ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}
      >
        <span>{label}</span>
        {sortBy === col && (sortDir === 'asc'
          ? <ArrowUp className="w-3 h-3" aria-hidden />
          : <ArrowDown className="w-3 h-3" aria-hidden />)}
      </button>
    </th>
  );

  return (
    <div className="rounded-xl bg-surface overflow-hidden border border-border/80 overflow-x-auto md:overflow-hidden">
      <table className="w-full table-fixed border-collapse" aria-label="文件列表">
        <colgroup>
          <col className="w-12" />
          <col />
          <col className="w-28" />
          <col className="w-44" />
          <col className="w-12" />
        </colgroup>
        <thead className="bg-surface-2/40">
          <tr className="border-b border-border">
            <th scope="col" className="h-10 px-3">
              <button
                onClick={onSelectAll}
                aria-label="全选"
                className={cn(
                  'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center cursor-pointer transition-colors',
                  allSelected
                    ? 'bg-primary-600 border-primary-600'
                    : someSelected
                      ? 'bg-primary-100 border-primary-400'
                      : 'border-border hover:border-primary-500',
                )}
              >
                {allSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
                {someSelected && !allSelected && <div className="w-2 h-0.5 bg-primary-600 rounded" />}
              </button>
            </th>
            {renderSortHeader('name', '名称')}
            {renderSortHeader('size', '大小', 'right')}
            {renderSortHeader('time', '修改时间')}
            <th scope="col" className="h-10" aria-label="操作" />
          </tr>
        </thead>
        <tbody>
          {files.map((file) => {
            const isSelected = selectedIds.has(file.id);
            return (
              <tr
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
                className={cn(
                  'group relative border-b border-border transition-colors duration-150',
                  isSelected
                    ? 'bg-primary-500/15'
                    : dragOverFolderId === file.id
                      ? 'bg-primary-500/10'
                      : 'hover:bg-surface-2/70',
                  focusedId === file.id && !isSelected && dragOverFolderId !== file.id && 'bg-primary-500/5',
                  cutIds?.has(file.id) && 'opacity-50',
                )}
              >
                {isSelected && <span className="absolute left-0 top-2 bottom-2 w-[3px] rounded-r bg-primary-600" aria-hidden />}

                <td className="px-3 py-0 align-middle">
                  <button
                    onClick={(e) => { e.stopPropagation(); onToggleSelect(file.id); }}
                    aria-label="选择"
                    className={cn(
                      'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center transition-[background-color,border-color,opacity]',
                      isSelected ? 'bg-primary-600 border-primary-600 opacity-100' : 'border-border bg-surface opacity-0 group-hover:opacity-100',
                    )}
                  >
                    {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
                  </button>
                </td>

                <td className="px-3 py-0 align-middle">
                  <div className="flex items-center gap-3 min-w-0">
                    <FileThumbnail file={file} size="lg" />
                    <div className={cn('text-sm truncate', isSelected ? 'text-primary-600 font-medium' : 'text-fg')}>
                      {file.name}
                    </div>
                  </div>
                </td>

                <td className="px-3 py-0 align-middle text-right text-xs text-muted tabular-nums whitespace-nowrap">
                  {file.nodeType === 0 ? '-' : formatSize(file.fileSize)}
                </td>
                <td className="px-3 py-0 align-middle text-xs text-muted tabular-nums truncate">
                  {formatDate(file.updatedAt)}
                </td>

                <td className="px-1 py-0 align-middle text-right">
                  {/* 收藏按钮：已收藏时常驻显示，未收藏时悬停显示 */}
                  <button
                    onClick={(e) => { e.stopPropagation(); onToggleFavorite(file); }}
                    aria-label={isFavorite(file.id) ? '取消收藏' : '收藏'}
                    title={isFavorite(file.id) ? '取消收藏' : '收藏'}
                    className={cn(
                      'inline-flex w-8 h-8 rounded-lg items-center justify-center transition-[background-color,color,opacity]',
                      isFavorite(file.id)
                        ? 'text-yellow-400 opacity-100'
                        : 'text-muted hover:text-yellow-400 opacity-0 group-hover:opacity-100',
                    )}
                  >
                    <Star className="w-4 h-4" fill={isFavorite(file.id) ? 'currentColor' : 'none'} aria-hidden />
                  </button>
                  {onItemMenu && (
                    <button
                      onClick={(e) => { e.stopPropagation(); onItemMenu(e, file); }}
                      onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); onContextMenu(e, file); }}
                      aria-label="更多操作"
                      className="w-8 h-8 rounded-lg inline-flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 opacity-0 group-hover:opacity-100 transition-[background-color,color,opacity]"
                    >
                      <MoreHorizontal className="w-4 h-4" aria-hidden />
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}