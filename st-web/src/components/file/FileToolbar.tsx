import { memo, useState } from 'react';
import { FolderPlus, Upload, Download, Trash2, Copy, FolderInput, X, RefreshCw, ArrowDownUp, List, LayoutGrid, Columns3, Edit3, Plus, ChevronDown, FileType, FileText, FileSpreadsheet, Presentation, MoreHorizontal } from 'lucide-react';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import { Popover, PopoverTrigger, PopoverContent } from '../ui/popover';
import { cn, formatSize } from '../../lib/utils';
import { Switch } from '../ui/switch';
import type { BlankFileType } from '../../types';

export type SortBy = 'name' | 'size' | 'time';
export type SortDir = 'asc' | 'desc';
export type ViewMode = 'list' | 'grid' | 'waterfall';
/** 网格视图图标大小：小 / 中 / 大 */
export type IconSize = 'sm' | 'md' | 'lg';

const SORT_OPTIONS: Array<{ value: SortBy; label: string }> = [
  { value: 'name', label: '名称' },
  { value: 'size', label: '大小' },
  { value: 'time', label: '修改时间' },
];

interface FileToolbarProps {
  has: (perm: string) => boolean;
  selectedCount: number;
  filesCount: number;
  allSelected: boolean;
  selectedSize: number;
  /** 当前选中单个可在线编辑文件（docx/xlsx/pptx/pdf 且有编辑权限）时可用 */
  canEditSelected: boolean;
  onEdit: () => void;
  sortBy: SortBy;
  onSortChange: (v: SortBy) => void;
  sortDir: SortDir;
  onSortDirToggle: () => void;
  view: ViewMode;
  waterfallAvailable: boolean;
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

function FileToolbar({
  has, selectedCount, filesCount, allSelected, selectedSize, canEditSelected, onEdit,
  sortBy, onSortChange, sortDir, onSortDirToggle,
  view, waterfallAvailable, onViewChange,
  onNewFolder, onNewFile, onUploadClick, onDownload, onMove, onCopy, onDelete,
  onSelectAll, onClearSelection, onRefresh, refreshing = false, onBatchRename,
  foldersFirst, onToggleFoldersFirst,
}: FileToolbarProps) {
  const hasSelection = selectedCount > 0;
  const [newMenuOpen, setNewMenuOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const [sortMenuOpen, setSortMenuOpen] = useState(false);

  return (
    <div className="flex items-center overflow-x-auto min-w-0">
      {/* 左：上传（主操作）/ 新建 */}
      <div className="flex items-center gap-1.5 flex-shrink-0">
        {has('file:upload') && (
          <button onClick={onUploadClick} className="btn-primary flex-shrink-0 whitespace-nowrap">
            <Upload className="w-4 h-4" aria-hidden />
            <span className="hidden sm:inline">上传文件</span>
            <span className="sm:hidden">上传</span>
          </button>
        )}
        {has('file:upload') && (
          <Popover open={newMenuOpen} onOpenChange={setNewMenuOpen}>
            <PopoverTrigger asChild>
              <button className="btn-secondary flex-shrink-0 whitespace-nowrap">
                <Plus className="w-4 h-4" aria-hidden />
                <span>新建</span>
                <ChevronDown className="w-3.5 h-3.5" aria-hidden />
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-44 p-1" align="start" sideOffset={4}>
              <button
                onClick={() => { setNewMenuOpen(false); onNewFolder(); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors"
              >
                <FolderPlus className="w-4 h-4" aria-hidden />
                <span>新建文件夹</span>
              </button>
              <div className="my-1 border-t border-border" />
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
      </div>

      {/* 中：选中文件时只保留高频操作，低频批量动作放入更多菜单。 */}
      <div className="hidden md:flex items-center gap-1.5 flex-1 min-w-0">
        {hasSelection && (
          <>
            <div className="w-px h-5 bg-border-light mx-1.5 flex-shrink-0" />
            <div className="flex items-center gap-1.5 px-2 py-1 bg-primary-50 dark:bg-primary-500/10 rounded-md text-xs text-primary-strong flex-shrink-0">
              <span className="font-medium">已选 {selectedCount} 项</span>
              {selectedSize > 0 && <span className="text-primary-400">· {formatSize(selectedSize)}</span>}
              <button onClick={onClearSelection} aria-label="取消选择" className="text-primary-400 hover:text-primary-strong cursor-pointer ml-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">
                <X className="w-3.5 h-3.5" aria-hidden />
              </button>
            </div>
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
            {has('file:move') && (
              <button onClick={onMove} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                <FolderInput className="w-4 h-4" aria-hidden />
                <span>移动到</span>
              </button>
            )}
            {has('file:copy') && (
              <button onClick={onCopy} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                <Copy className="w-4 h-4" aria-hidden />
                <span>复制到</span>
              </button>
            )}
            <Popover open={actionsOpen} onOpenChange={setActionsOpen}>
              <PopoverTrigger asChild>
                <button className="btn-ghost flex-shrink-0 whitespace-nowrap" aria-label="更多批量操作">
                  <MoreHorizontal className="w-4 h-4" aria-hidden />
                  <span className="hidden sm:inline">更多</span>
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-44 p-1" align="start" sideOffset={4}>
                {selectedCount > 1 && has('file:rename') && (
                  <button onClick={() => { setActionsOpen(false); onBatchRename(); }} className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors">
                    <Edit3 className="w-4 h-4" aria-hidden />
                    <span>批量重命名</span>
                  </button>
                )}
                {!allSelected && filesCount > 1 && (
                  <button onClick={() => { setActionsOpen(false); onSelectAll(); }} className="w-full text-left px-3 py-1.5 text-sm text-fg hover:bg-surface-2 rounded-md cursor-pointer transition-colors">
                    全选当前页
                  </button>
                )}
                <button onClick={() => { setActionsOpen(false); onClearSelection(); }} className="w-full text-left px-3 py-1.5 text-sm text-muted hover:bg-surface-2 rounded-md cursor-pointer transition-colors">
                  取消选择
                </button>
              </PopoverContent>
            </Popover>
            {has('file:delete') && (
              <button onClick={onDelete} className="btn-ghost text-red-600 dark:text-red-400 hover:bg-red-500/10 flex-shrink-0 whitespace-nowrap">
                <Trash2 className="w-4 h-4" aria-hidden />
                <span>删除</span>
              </button>
            )}
          </>
        )}
      </div>

      {/* 右：排序 / 刷新 / 视图切换 */}
      <div className="flex items-center gap-1.5 md:gap-3 flex-shrink-0">
        {!hasSelection && (
          <div className="hidden md:flex items-center gap-1.5">
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
              <span className="hidden xl:inline">文件夹优先</span>
            </label>
          </div>
        )}
        {/* 移动端排序入口：桌面端排序控件在 <md 隐藏，用 Popover 补齐排序能力 */}
        {!hasSelection && (
          <div className="md:hidden">
            <Popover open={sortMenuOpen} onOpenChange={setSortMenuOpen}>
              <PopoverTrigger asChild>
                <button
                  className="btn-ghost w-9 h-9 px-0 flex-shrink-0"
                  aria-label="排序"
                  title="排序"
                >
                  <ArrowDownUp className="w-4 h-4" aria-hidden />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-60 p-2 md:hidden" align="end" sideOffset={4}>
                <div className="px-2 pt-1 pb-1.5 text-xs font-medium text-muted">排序方式</div>
                <div className="flex flex-col gap-0.5">
                  {SORT_OPTIONS.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => { onSortChange(option.value); setSortMenuOpen(false); }}
                      aria-pressed={sortBy === option.value}
                      className={cn(
                        'w-full flex items-center justify-between px-2 py-2 rounded-md text-sm text-left cursor-pointer transition-colors',
                        sortBy === option.value ? 'bg-surface-2 text-primary-strong font-medium' : 'text-fg hover:bg-surface-2',
                      )}
                    >
                      <span>{option.label}</span>
                      {sortBy === option.value && <span aria-hidden className="text-primary-strong">●</span>}
                    </button>
                  ))}
                </div>
                <div className="my-1 border-t border-border" />
                <button
                  onClick={onSortDirToggle}
                  aria-label="切换排序方向"
                  className="w-full flex items-center justify-between px-2 py-2 rounded-md text-sm text-fg hover:bg-surface-2 cursor-pointer transition-colors"
                >
                  <span>排序方向</span>
                  <span className="inline-flex items-center gap-1 text-xs text-muted">
                    {sortDir === 'asc' ? '升序' : '降序'}
                    <span aria-hidden>{sortDir === 'asc' ? '↑' : '↓'}</span>
                  </span>
                </button>
                <label className="flex items-center justify-between gap-2 px-2 py-2 text-sm text-fg cursor-pointer select-none">
                  <span>文件夹优先</span>
                  <Switch checked={foldersFirst} onCheckedChange={onToggleFoldersFirst} aria-label="文件夹优先" />
                </label>
              </PopoverContent>
            </Popover>
          </div>
        )}
        <button onClick={onRefresh} disabled={refreshing} aria-label="刷新" className="btn-ghost" title="刷新">
          <RefreshCw className={cn('w-4 h-4', refreshing && 'animate-spin')} aria-hidden />
        </button>
        {!hasSelection && (
          <div className="flex items-center bg-surface-2 rounded-lg p-0.5">
            <button
              onClick={() => onViewChange('list')} aria-label="列表视图" title="列表视图"
              className={cn('w-9 h-9 flex items-center justify-center rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'list' ? 'bg-surface text-primary-strong shadow-soft' : 'text-muted hover:text-fg')}
            >
              <List className="w-4 h-4" aria-hidden />
            </button>
            <button
              onClick={() => onViewChange('grid')} aria-label="网格视图" title="网格视图"
              className={cn('w-9 h-9 flex items-center justify-center rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'grid' ? 'bg-surface text-primary-strong shadow-soft' : 'text-muted hover:text-fg')}
            >
              <LayoutGrid className="w-4 h-4" aria-hidden />
            </button>
            {waterfallAvailable && <button
              onClick={() => onViewChange('waterfall')} aria-label="瀑布流视图" title="瀑布流视图"
              className={cn('w-9 h-9 flex items-center justify-center rounded-md cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', view === 'waterfall' ? 'bg-surface text-primary-strong shadow-soft' : 'text-muted hover:text-fg')}
            >
              <Columns3 className="w-4 h-4" aria-hidden />
            </button>}
          </div>
        )}
      </div>
    </div>
  );
}

/** 工具栏：props 引用稳定时跳过重渲染，减少文件列表操作时的重绘 */
export default memo(FileToolbar);
