import { memo, type RefObject } from 'react';
import type { FileNode } from '../../types';
import type { SortBy, SortDir, ViewMode, IconSize } from './FileToolbar';
import FileTableView from './FileTableView';
import FileGrid from './FileGrid';

/** 三种视图（表格/列表/网格）的公共 props，避免调用方重复传参 */
export interface FileListProps {
  view: ViewMode;
  iconSize: IconSize;
  files: FileNode[];
  lockedIds: Set<string>;
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  sortBy: SortBy;
  sortDir: SortDir;
  onSortChange: (col: SortBy) => void;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onToggleSelect: (id: string) => void;
  onSelectAll: () => void;
  isFavorite: (id: string) => boolean;
  onToggleFavorite: (node: FileNode) => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
  onItemDragStart: (e: React.DragEvent, node: FileNode) => void;
  onFolderDragOver: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDragLeave: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDrop: (e: React.DragEvent, folder: FileNode) => void;
  dragOverFolderId: string | null;
  /** 列表滚动容器（FileBrowser 传入，用于虚拟滚动） */
  scrollRef?: RefObject<HTMLDivElement | null>;
}

/** 按当前视图渲染文件列表（统一 onDoubleClick/拖拽等行为） */
function FileList({ view, iconSize, onSelectAll, scrollRef, ...common }: FileListProps) {
  if (view === 'list') return <FileTableView {...common} onSelectAll={onSelectAll} scrollRef={scrollRef} />;
  return <FileGrid {...common} iconSize={iconSize} />;
}

export default memo(FileList);
