import { CheckSquare, Download, Trash2, X, Share2 } from 'lucide-react';
import { cn } from '../../lib/utils';

interface MultiSelectBarProps {
  selectedCount: number;
  allSelected: boolean;
  onSelectAll: () => void;
  onDownload: () => void;
  onDelete: () => void;
  onShare: () => void;
  onCancel: () => void;
  canDownload: boolean;
  canDelete: boolean;
  canShare: boolean;
}

/**
 * 移动端多选操作栏
 * 长按文件进入多选模式后,顶部出现此操作栏
 * 仅移动端(md 以下)显示
 */
export default function MultiSelectBar({
  selectedCount,
  allSelected,
  onSelectAll,
  onDownload,
  onDelete,
  onShare,
  onCancel,
  canDownload,
  canDelete,
  canShare,
}: MultiSelectBarProps) {
  return (
    <div className="md:hidden fixed top-14 inset-x-0 z-30 bg-surface border-b border-border px-3 py-2 flex items-center gap-2 pt-safe">
      <button
        onClick={onCancel}
        className="p-2 text-muted hover:text-fg rounded-lg cursor-pointer min-h-[44px] min-w-[44px] flex items-center justify-center"
        aria-label="取消多选"
      >
        <X className="w-5 h-5" aria-hidden />
      </button>
      <span className="text-sm font-medium text-fg flex-shrink-0">
        已选 {selectedCount}
      </span>
      <div className="flex-1" />
      <button
        onClick={onSelectAll}
        className="flex items-center gap-1.5 px-3 py-2 text-sm text-muted hover:text-fg rounded-lg cursor-pointer min-h-[44px]"
        aria-label={allSelected ? '取消全选' : '全选'}
      >
        <CheckSquare className="w-[18px] h-[18px]" aria-hidden />
        <span className="hidden xs:inline">{allSelected ? '取消全选' : '全选'}</span>
      </button>
      {canDownload && (
        <button
          onClick={onDownload}
          disabled={selectedCount === 0}
          className="flex items-center gap-1.5 px-3 py-2 text-sm text-muted hover:text-fg rounded-lg cursor-pointer disabled:opacity-40 min-h-[44px]"
          aria-label="下载"
        >
          <Download className="w-[18px] h-[18px]" aria-hidden />
        </button>
      )}
      {canShare && (
        <button
          onClick={onShare}
          disabled={selectedCount === 0}
          className="flex items-center gap-1.5 px-3 py-2 text-sm text-muted hover:text-fg rounded-lg cursor-pointer disabled:opacity-40 min-h-[44px]"
          aria-label="分享"
        >
          <Share2 className="w-[18px] h-[18px]" aria-hidden />
        </button>
      )}
      {canDelete && (
        <button
          onClick={onDelete}
          disabled={selectedCount === 0}
          className={cn(
            'flex items-center gap-1.5 px-3 py-2 text-sm rounded-lg cursor-pointer disabled:opacity-40 min-h-[44px]',
            'text-red-500 hover:bg-red-500/10'
          )}
          aria-label="删除"
        >
          <Trash2 className="w-[18px] h-[18px]" aria-hidden />
        </button>
      )}
    </div>
  );
}