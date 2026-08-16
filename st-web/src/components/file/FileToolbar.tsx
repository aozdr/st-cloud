import { useState } from 'react';
import { FolderPlus, Upload, Download, Trash2, Copy, FolderInput, X, RefreshCw, ArrowDownUp, Table2, Rows3, LayoutGrid, Edit3, Plus, ChevronDown, MoreHorizontal, FileType, FileText, FileSpreadsheet, Presentation } from 'lucide-react';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import { Popover, PopoverTrigger, PopoverContent } from '../ui/popover';
import { cn, formatSize } from '../../lib/utils';
import { Switch } from '../ui/switch';
import type { BlankFileType } from '../../types';

export type SortBy = 'name' | 'size' | 'time';
export type SortDir = 'asc' | 'desc';
export type ViewMode = 'table' | 'card' | 'grid';

interface FileToolbarProps {
  has: (perm: string) => boolean;
  selectedCount: number;
  filesCount: number;
  allSelected: boolean;
  selectedSize: number;
  /** 当前选中单个可在线编辑文件（docx/xlsx/pptx 且有编辑权限）时可用 */
  canEditSelected: boolean;
  onEdit: () => void;
  sortBy: SortBy;
  onSortChange: (v: SortBy) => void;
  sortDir: SortDir;
  onSortDirToggle: () => void;
  view: ViewMode;
  onViewChange: (v: ViewMode) => void;
  onNewFolder: () => void;
  /** 新建空白文件：txt/docx/xlsx/pptx（成功后 Office 类型由 FileBrowser 决定跳转编辑） */
  onNewFile: (type: BlankFileType) => void;
  onUploadClick: () => void;
  onDownload: () => void;
  onMove: () => void;
  onCopy: () => void;
  onDelete: () => void;
  onSelectAll: () => void;
  onClearSelection: () => void;
  onRefresh: () => void;
  /** 刷新中：刷新按钮图标旋转（Windows 风格反馈） */
  refreshing?: boolean;
  onBatchRename: () => void;
  foldersFirst: boolean;
  onToggleFoldersFirst: (v: boolean) => void;
}

export default function FileToolbar({
  has, selectedCount, filesCount, allSelected, selectedSize, canEditSelected, onEdit,
  sortBy, onSortChange, sortDir, onSortDirToggle,
  view, onViewChange,
  onNewFolder, onNewFile, onUploadClick, onDownload, onMove, onCopy, onDelete,
  onSelectAll, onClearSelection, onRefresh, refreshing = false, onBatchRename,
  foldersFirst, onToggleFoldersFirst,
}: FileToolbarProps) {
  const hasSelection = selectedCount > 0;
  const [newMenuOpen, setNewMenuOpen] = useState(false);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  // 低频批量操作收进「更多」菜单，降低工具栏密度
  const hasMoreActions = hasSelection && (
    has('file:move') || has('file:copy') || (selectedCount > 1 && has('file:rename'))
  );

  return (
    <div className="sticky top-0 z-20 flex items-center justify-between gap-2 px-5 py-2 bg-surface border-b border-border/60 overflow-x-auto">
      <div className="flex items-center gap-1.5 flex-shrink-0">
        {has('file:upload') && (
          <Popover open={newMenuOpen} onOpenChange={setNewMenuOpen}>
            <PopoverTrigger asChild>
              <button className="btn-primary flex-shrink-0 whitespace-nowrap">
                <Plus className="w-4 h-4" aria-hidden />
                <span>新建</span>
                <ChevronDown className="w-3.5 h-3.5" aria-hidden />
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-44 p-1" align="start" sideOffset={4}>
              {/* 新建文件夹：保留原入口 */}
              <button
                onClick={() => { setNewMenuOpen(false); onNewFolder(); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <FolderPlus className="w-4 h-4" aria-hidden />
                <span>新建文件夹</span>
              </button>
              <div className="my-1 border-t border-border" />
              {/* 新建空白文件：txt 留在列表；docx/xlsx/pptx 由 FileBrowser 跳转在线编辑（P3） */}
              <button
                onClick={() => { setNewMenuOpen(false); onNewFile('txt'); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <FileType className="w-4 h-4" aria-hidden />
                <span>文本文档</span>
              </button>
              <button
                onClick={() => { setNewMenuOpen(false); onNewFile('docx'); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <FileText className="w-4 h-4" aria-hidden />
                <span>Word 文档</span>
              </button>
              <button
                onClick={() => { setNewMenuOpen(false); onNewFile('xlsx'); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <FileSpreadsheet className="w-4 h-4" aria-hidden />
                <span>Excel 表格</span>
              </button>
              <button
                onClick={() => { setNewMenuOpen(false); onNewFile('pptx'); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <Presentation className="w-4 h-4" aria-hidden />
                <span>PPT 演示</span>
              </button>
            </PopoverContent>
          </Popover>
        )}
        {has('file:upload') && (
          <button onClick={onUploadClick} className="btn-ghost flex-shrink-0 whitespace-nowrap">
            <Upload className="w-4 h-4" aria-hidden />
            <span>上传文件</span>
          </button>
        )}
        {hasSelection && (
          <>
            <div className="w-px h-5 bg-surface-2 mx-1.5 flex-shrink-0" />
            {canEditSelected && (
              <button onClick={onEdit} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                <Edit3 className="w-4 h-4" aria-hidden />
                <span>在线编辑</span>
              </button>
            )}
            {has('file:download') && (
              <button onClick={onDownload} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                <Download className="w-4 h-4" aria-hidden />
                <span>下载</span>
              </button>
            )}
            {hasMoreActions && (
              <Popover open={moreMenuOpen} onOpenChange={setMoreMenuOpen}>
                <PopoverTrigger asChild>
                  <button className="btn-ghost flex-shrink-0 whitespace-nowrap">
                    <MoreHorizontal className="w-4 h-4" aria-hidden />
                    <span>更多</span>
                  </button>
                </PopoverTrigger>
                <PopoverContent className="w-44 p-1" align="start" sideOffset={4}>
                  {has('file:move') && (
                    <button
                      onClick={() => { setMoreMenuOpen(false); onMove(); }}
                      className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
                    >
                      <FolderInput className="w-4 h-4" aria-hidden />
                      <span>移动到</span>
                    </button>
                  )}
                  {has('file:copy') && (
                    <button
                      onClick={() => { setMoreMenuOpen(false); onCopy(); }}
                      className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
                    >
                      <Copy className="w-4 h-4" aria-hidden />
                      <span>复制到</span>
                    </button>
                  )}
                  {selectedCount > 1 && has('file:rename') && (
                    <button
                      onClick={() => { setMoreMenuOpen(false); onBatchRename(); }}
                      className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
                    >
                      <Edit3 className="w-4 h-4" aria-hidden />
                      <span>批量重命名</span>
                    </button>
                  )}
                </PopoverContent>
              </Popover>
            )}
            {has('file:delete') && (
              <button onClick={onDelete} className="btn-ghost text-red-600 dark:text-red-400 hover:bg-red-500/10 flex-shrink-0 whitespace-nowrap">
                <Trash2 className="w-4 h-4" aria-hidden />
                <span>删除</span>
              </button>
            )}
          </>
        )}
      </div>

      <div className="flex items-center gap-3 flex-shrink-0">
        {!hasSelection && view !== 'table' && (
          <div className="flex items-center gap-1.5">
            <ArrowDownUp className="w-3.5 h-3.5 text-muted" aria-hidden />
            <Select value={sortBy} onValueChange={(v) => onSortChange(v as SortBy)}>
              <SelectTrigger className="h-7 w-auto gap-1 text-xs border-border px-2.5 py-0.5 font-medium text-muted hover:border-border">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="min-w-[7rem]">
                <SelectItem value="name">名称</SelectItem>
                <SelectItem value="size">大小</SelectItem>
                <SelectItem value="time">修改时间</SelectItem>
              </SelectContent>
            </Select>
            <button
              onClick={onSortDirToggle}
              className="text-muted hover:text-fg cursor-pointer px-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
              title={sortDir === 'asc' ? '升序' : '降序'}
            >
              {sortDir === 'asc' ? '↑' : '↓'}
            </button>
            <label className="flex items-center gap-1 text-xs text-muted cursor-pointer select-none whitespace-nowrap">
              <Switch checked={foldersFirst} onCheckedChange={onToggleFoldersFirst} aria-label="文件夹优先" />
              <span>文件夹优先</span>
            </label>
          </div>
        )}
        {hasSelection && (
          <>
            {!allSelected && filesCount > 1 && (
              <button onClick={onSelectAll} className="text-xs text-primary-600 hover:text-primary-600 cursor-pointer font-medium whitespace-nowrap">
                全选
              </button>
            )}
            <div className="flex items-center gap-2 px-2.5 py-1 bg-primary-500/10 rounded-lg text-sm text-primary-600">
              <span className="font-medium">已选 {selectedCount} 项</span>
              {selectedSize > 0 && <span className="text-primary-400">· {formatSize(selectedSize)}</span>}
              <button onClick={onClearSelection} aria-label="取消选择" className="text-primary-400 hover:text-primary-600 cursor-pointer ml-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">
                <X className="w-3.5 h-3.5" aria-hidden />
              </button>
            </div>
          </>
        )}
        <button onClick={onRefresh} disabled={refreshing} aria-label="刷新" className="btn-ghost" title="刷新">
          <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} aria-hidden />
        </button>
        {!hasSelection && (
          <div className="flex items-center bg-surface-2 rounded-lg p-0.5">
            <button
              onClick={() => onViewChange('table')} aria-label="表格视图" title="表格视图"
              className={cn('p-1.5 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'table' ? 'bg-surface text-primary-600 shadow-sm' : 'text-muted hover:text-fg')}
            >
              <Table2 className="w-4 h-4" aria-hidden />
            </button>
            <button
              onClick={() => onViewChange('card')} aria-label="列表视图" title="列表视图"
              className={cn('p-1.5 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'card' ? 'bg-surface text-primary-600 shadow-sm' : 'text-muted hover:text-fg')}
            >
              <Rows3 className="w-4 h-4" aria-hidden />
            </button>
            <button
              onClick={() => onViewChange('grid')} aria-label="网格视图" title="网格视图"
              className={cn('p-1.5 rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'grid' ? 'bg-surface text-primary-600 shadow-sm' : 'text-muted hover:text-fg')}
            >
              <LayoutGrid className="w-4 h-4" aria-hidden />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
