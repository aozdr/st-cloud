import { memo, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { formatSize, formatDate, cn, getFileTypeConfig } from '../../lib/utils';
import type { FileNode } from '../../types';
import type { SortBy, SortDir } from './FileToolbar';
import { Check, MoreHorizontal, ArrowUp, ArrowDown, Star, Lock } from 'lucide-react';
import { useAuthStore } from '../../store/auth';
import FileThumbnail from './FileThumbnail';

interface Props {
  files: FileNode[];
  lockedIds?: Set<string>;
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
  scrollRef?: RefObject<HTMLDivElement | null>;
}

function FileTableView({
  files, lockedIds, selectedIds, focusedId, cutIds, sortBy, sortDir, onSortChange,
  onSelect, onSelectAll, onContextMenu, onDoubleClick,
  onItemDragStart, onFolderDragOver, onFolderDragLeave, onFolderDrop, dragOverFolderId, onItemMenu,
  onToggleSelect, isFavorite, onToggleFavorite, scrollRef,
}: Props) {
  const user = useAuthStore((s) => s.user);
  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const someSelected = files.some((f) => selectedIds.has(f.id));
  const rowHeight = 52;

  const listRef = useRef<HTMLDivElement>(null);
  const [scrollMargin, setScrollMargin] = useState(0);
  const virtualizer = useVirtualizer({
    count: files.length,
    getScrollElement: () => scrollRef?.current ?? null,
    estimateSize: () => rowHeight,
    overscan: 8,
    scrollMargin,
  });

  useLayoutEffect(() => {
    const el = listRef.current;
    const scroller = scrollRef?.current;
    if (el && scroller) {
      setScrollMargin(el.getBoundingClientRect().top - scroller.getBoundingClientRect().top);
    }
  }, [scrollRef, files.length]);

  const renderSortHeader = (col: SortBy, label: string, align?: 'right', hiddenClass?: string) => (
    <div
      role="columnheader"
      className={cn('h-10 px-4 text-xs font-medium text-tertiary select-none text-left flex items-center', hiddenClass, align === 'right' && 'text-right justify-end')}
    >
      <button
        type="button"
        onClick={() => onSortChange(col)}
        className={cn(
          'inline-flex items-center gap-1 transition-colors cursor-pointer hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          align === 'right' && 'flex-row-reverse',
        )}
        aria-sort={sortBy === col ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}
      >
        <span>{label}</span>
        {sortBy === col && (sortDir === 'asc'
          ? <ArrowUp className="w-3.5 h-3.5" aria-hidden />
          : <ArrowDown className="w-3.5 h-3.5" aria-hidden />)}
      </button>
    </div>
  );

  return (
    <div className="bg-[#FEFEFD] dark:bg-surface overflow-x-auto">
      <div role="table" className="w-full min-w-[760px]">
        {/* 表头 */}
        <div role="row" className="flex items-center bg-[#F8FAFC] dark:bg-surface-2 border-t border-b border-border">
          <div role="columnheader" className="w-11 px-4 h-10 flex items-center">
            <button
              onClick={onSelectAll}
              aria-label="全选"
              role="checkbox"
              aria-checked={allSelected ? 'true' : someSelected ? 'mixed' : 'false'}
              className={cn(
                'w-4 h-4 rounded border flex items-center justify-center cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                allSelected
                  ? 'bg-primary-600 border-primary-600'
                  : someSelected
                    ? 'bg-primary-100 border-primary-300'
                    : 'border-[#CDD2DC] hover:border-primary-400 bg-surface',
              )}
            >
              {allSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
              {someSelected && !allSelected && <div className="w-2 h-0.5 bg-primary-600 rounded" />}
            </button>
          </div>
          {renderSortHeader('name', '名称')}
          <div className="hidden sm:flex w-28 px-4 h-10 text-xs font-medium text-tertiary items-center select-none">类型</div>
          {renderSortHeader('size', '大小', 'right', 'hidden sm:flex')}
          {renderSortHeader('time', '修改时间', undefined, 'hidden md:flex')}
          <div className="hidden lg:flex w-32 px-4 h-10 text-xs font-medium text-tertiary items-center select-none">所有者</div>
          <div role="columnheader" className="w-24 h-10" aria-label="操作" />
        </div>

        {/* 虚拟行容器 */}
        <div ref={listRef} className="relative" style={{ height: virtualizer.getTotalSize() }}>
          {virtualizer.getVirtualItems().map((vi) => {
            const file = files[vi.index];
            if (!file) return null;
            const isSelected = selectedIds.has(file.id);
            const config = getFileTypeConfig(file.nodeType, file.suffix);
            const owner = file.ownerName || user?.nickname || user?.username || '我';
            return (
              <div
                key={file.id}
                role="row"
                data-index={vi.index}
                data-file-id={file.id}
                style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: rowHeight, transform: `translateY(${vi.start - scrollMargin}px)` }}
                className={cn(
                  'flex items-center border-b border-border last:border-0 transition-colors duration-150',
                  isSelected
                    ? 'bg-[#EEF0FF] dark:bg-primary-950/40'
                    : dragOverFolderId === file.id
                      ? 'bg-primary-500/10 ring-2 ring-primary-400 ring-inset'
                      : 'hover:bg-[#F8FAFF] dark:hover:bg-surface-2',
                  focusedId === file.id && !isSelected && dragOverFolderId !== file.id && 'bg-primary-500/5',
                  cutIds?.has(file.id) && 'opacity-50',
                )}
                draggable
                onDragStart={(e) => onItemDragStart?.(e, file)}
                onDragOver={file.nodeType === 0 ? (e) => onFolderDragOver?.(e, file) : undefined}
                onDragLeave={file.nodeType === 0 ? (e) => onFolderDragLeave?.(e, file) : undefined}
                onDrop={file.nodeType === 0 ? (e) => onFolderDrop?.(e, file) : undefined}
                onClick={(e) => onSelect(file.id, e)}
                onDoubleClick={() => onDoubleClick(file)}
                onContextMenu={(e) => onContextMenu(e, file)}
              >
                <div className="w-11 px-4 flex items-center">
                  <button
                    onClick={(e) => { e.stopPropagation(); onToggleSelect(file.id); }}
                    aria-label="选择"
                    className={cn(
                      'w-4 h-4 rounded border flex items-center justify-center transition-[background-color,border-color,opacity] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:opacity-100',
                      isSelected ? 'bg-primary-600 border-primary-600 opacity-100' : 'border-[#CDD2DC] bg-surface opacity-0 group-hover:opacity-100',
                    )}
                  >
                    {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
                  </button>
                </div>
                <div className="flex-1 min-w-0 px-4 flex items-center gap-3">
                  <FileThumbnail file={file} size="lg" className="flex-shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className={cn('flex items-center gap-1 min-w-0 text-sm leading-5 font-medium', isSelected ? 'text-primary-600' : 'text-fg')}>
                      <span className="min-w-0 truncate">{file.name}</span>
                      {lockedIds?.has(file.id) && <Lock className="w-3.5 h-3.5 text-amber-500 flex-shrink-0" aria-hidden />}
                    </div>
                  </div>
                </div>
                <div className="hidden sm:flex w-28 px-4 text-sm text-muted truncate items-center">{config.label}</div>
                <div className="hidden sm:flex w-24 px-4 text-right text-sm text-muted tabular-nums whitespace-nowrap items-center justify-end">
                  {file.nodeType === 0 ? '-' : formatSize(file.fileSize)}
                </div>
                <div className="hidden md:flex w-40 px-4 text-sm text-muted tabular-nums truncate items-center">{formatDate(file.updatedAt)}</div>
                <div className="hidden lg:flex w-32 px-4 text-sm text-muted truncate items-center">{owner}</div>
                <div className="flex w-24 px-3 items-center justify-end gap-1">
                  <button
                    onClick={(e) => { e.stopPropagation(); onToggleFavorite(file); }}
                    aria-label={isFavorite(file.id) ? '取消收藏' : '收藏'}
                    title={isFavorite(file.id) ? '取消收藏' : '收藏'}
                    className={cn(
                      'inline-flex w-8 h-8 rounded-lg items-center justify-center transition-[background-color,color,opacity] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:opacity-100',
                      isFavorite(file.id) ? 'text-amber-400 opacity-100' : 'text-tertiary hover:text-amber-400 opacity-0 group-hover:opacity-100',
                    )}
                  >
                    <Star className="w-4 h-4" fill={isFavorite(file.id) ? 'currentColor' : 'none'} aria-hidden />
                  </button>
                  {onItemMenu && (
                    <button
                      onClick={(e) => { e.stopPropagation(); onItemMenu(e, file); }}
                      onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); onContextMenu(e, file); }}
                      aria-label="更多操作"
                      className="w-8 h-8 rounded-lg inline-flex items-center justify-center text-tertiary hover:text-fg hover:bg-surface-2 opacity-0 group-hover:opacity-100 transition-[background-color,color,opacity] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:opacity-100"
                    >
                      <MoreHorizontal className="w-4 h-4" aria-hidden />
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export default memo(FileTableView);
