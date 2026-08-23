import { cn, formatSize, isVideo } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileThumbnail from './FileThumbnail';
import type { SortBy, SortDir, IconSize } from './FileToolbar';
import { Check, Star, Play, Lock } from 'lucide-react';

/** 每种图标尺寸对应的网格列数、图标大小与文案密度 */
const GRID_SIZE_CFG: Record<IconSize, {
  grid: string;
  thumb: 'xl' | 'xxl' | 'xxxl';
  cardPad: string;
  iconMb: string;
  name: string;
  meta: string;
}> = {
  // 小：更紧凑，单屏更多项，图标 48px
  sm: {
    grid: 'grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 2xl:grid-cols-8 gap-2 p-3',
    thumb: 'xl',
    cardPad: 'p-3',
    iconMb: 'mb-1',
    name: 'text-sm',
    meta: 'text-[11px]',
  },
  // 中：现状基线，图标 72px
  md: {
    grid: 'grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7 gap-3 p-4',
    thumb: 'xxl',
    cardPad: 'p-4',
    iconMb: 'mb-1.5',
    name: 'text-sm',
    meta: 'text-xs',
  },
  // 大：单屏更少项，图标 96px
  lg: {
    grid: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6 gap-4 p-5',
    thumb: 'xxxl',
    cardPad: 'p-5',
    iconMb: 'mb-2',
    name: 'text-base',
    meta: 'text-sm',
  },
};

interface Props {
  files: FileNode[];
  iconSize: IconSize;
  sortBy?: SortBy;
  sortDir?: SortDir;
  onSortChange?: (col: SortBy) => void;
  /** 已锁定节点 ID 集合（团队空间传入；用于列表行显示锁图标） */
  lockedIds?: Set<string>;
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
  onItemDragStart?: (e: React.DragEvent, node: FileNode) => void;
  onFolderDragOver?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDragLeave?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDrop?: (e: React.DragEvent, folder: FileNode) => void;
  dragOverFolderId?: string | null;
  onToggleSelect: (id: string) => void;
  isFavorite: (id: string) => boolean;
  onToggleFavorite: (node: FileNode) => void;
}

export default function FileGrid({
  files, iconSize, lockedIds, selectedIds, focusedId, cutIds, onSelect, onContextMenu, onDoubleClick,
  onItemDragStart, onFolderDragOver, onFolderDragLeave, onFolderDrop, dragOverFolderId,
  onToggleSelect, isFavorite, onToggleFavorite,
}: Props) {
  const cfg = GRID_SIZE_CFG[iconSize];

  return (
    <div className={cn('grid file-grid file-grid-' + iconSize, cfg.grid)}>
      {files.map((file) => {
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
            style={{ contentVisibility: 'auto', containIntrinsicSize: '160px' }}
            className={cn(
              'group relative flex flex-col rounded-[14px] bg-[#FEFEFD] dark:bg-surface border border-transparent cursor-pointer select-none transition-[background-color,border-color] duration-150',
              cfg.cardPad,
              isSelected
                ? 'bg-[#EEF0FF] dark:bg-primary-950/40 border-primary-400'
                : dragOverFolderId === file.id
                  ? 'bg-primary-500/10 border-primary-400'
                  : focusedId === file.id
                    ? 'bg-bg-hover border-primary-300'
                    : 'hover:bg-bg-hover hover:border-primary-200',
              cutIds?.has(file.id) && 'opacity-50',
            )}
          >
            <button
              onClick={(e) => { e.stopPropagation(); onToggleSelect(file.id); }}
              aria-label="选择"
              className={cn(
                'absolute top-3 left-3 z-10 w-4 h-4 rounded border flex items-center justify-center transition-[background-color,border-color,opacity] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:opacity-100',
                isSelected ? 'bg-primary-600 border-primary-600 opacity-100' : 'border-border bg-surface/90 opacity-0 group-hover:opacity-100',
              )}
            >
              {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
            </button>

            {/* 收藏星星图标：已收藏时常驻显示，未收藏时悬停显示 */}
            <button
              onClick={(e) => { e.stopPropagation(); onToggleFavorite(file); }}
              aria-label={isFavorite(file.id) ? '取消收藏' : '收藏'}
              title={isFavorite(file.id) ? '取消收藏' : '收藏'}
              className={cn(
                'absolute top-2 right-2 z-10 w-7 h-7 rounded-lg flex items-center justify-center bg-black/30 hover:bg-black/50 backdrop-blur-sm transition-opacity focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:opacity-100',
                isFavorite(file.id) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100',
              )}
            >
              <Star
                className={cn('w-3.5 h-3.5', isFavorite(file.id) ? 'text-yellow-400' : 'text-white')}
                fill={isFavorite(file.id) ? 'currentColor' : 'none'}
                aria-hidden
              />
            </button>

            {/* 图标容器：透明背景，不再用灰色底圈住图标 */}
            <div className={cn('relative aspect-video w-full rounded-2xl overflow-hidden bg-transparent', cfg.iconMb)}>
              {/* 居中容器：非图片文件图标居中展示；图片文件由 FileThumbnail 铺满 */}
              <div className="absolute inset-0 flex items-center justify-center">
                <FileThumbnail file={file} size={cfg.thumb} blur className="absolute inset-0 w-full h-full" />
              </div>
              {/* 视频播放按钮：半透明圆形 + 白色三角（点击进入预览播放器） */}
              {isVideo(file.suffix) && (
                <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                  <span className="w-12 h-12 rounded-full bg-black/50 flex items-center justify-center backdrop-blur-sm">
                    <Play className="w-5 h-5 text-white ml-0.5" fill="currentColor" aria-hidden />
                  </span>
                </div>
              )}
            </div>

            <div className="min-w-0">
              <div
                className={cn(
                  'flex items-center justify-center gap-1 leading-5 truncate text-center',
                  cfg.name,
                  isSelected ? 'text-primary-600 font-medium' : 'text-fg',
                )}
                title={file.name}
              >
                <span className="min-w-0 truncate">{file.name}</span>
                {lockedIds?.has(file.id) && <Lock className="w-3 h-3 text-amber-500 flex-shrink-0" aria-hidden />}
              </div>
              {file.nodeType === 1 && (
                <div className={cn('text-tertiary truncate text-center mt-0.5', cfg.meta)}>
                  {formatSize(file.fileSize)}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
