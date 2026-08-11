import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, type LucideIcon } from 'lucide-react';
import { cn } from '../../lib/utils';

export interface ActionSheetItem {
  label: string;
  icon?: LucideIcon;
  onClick: () => void;
  danger?: boolean;
}

interface ActionSheetProps {
  open: boolean;
  title?: string;
  items: ActionSheetItem[];
  onClose: () => void;
}

/**
 * 移动端底部操作菜单
 * 从底部滑入,半透明遮罩,点击遮罩或下滑关闭
 * 替代桌面端右键 ContextMenu
 */
export default function ActionSheet({ open, title, items, onClose }: ActionSheetProps) {
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    if (open) setClosing(false);
  }, [open]);

  if (!open) return null;

  const handleClose = () => {
    setClosing(true);
    onClose();
  };

  return createPortal(
    <div className="fixed inset-0 z-[60] flex flex-col justify-end" role="dialog" aria-modal="true">
      {/* 遮罩 */}
      <div
        className="absolute inset-0 bg-black/50 animate-fade-in"
        onClick={handleClose}
        aria-hidden="true"
      />
      {/* 菜单面板 */}
      <div
        className={cn(
          'relative bg-surface rounded-t-2xl shadow-float pb-safe',
          closing ? 'animate-sheet-down' : 'animate-sheet-up'
        )}
      >
        {title && (
          <div className="px-4 py-3 text-center text-sm font-medium text-fg border-b border-border">
            {title}
          </div>
        )}
        {/* 顶部拖拽指示条 */}
        <div className="flex justify-center pt-2 pb-1">
          <div className="w-10 h-1 rounded-full bg-border" />
        </div>
        <div className="px-2 pb-2">
          {items.map((item, idx) => (
            <button
              key={idx}
              onClick={() => {
                item.onClick();
                handleClose();
              }}
              className={cn(
                'w-full flex items-center gap-3 px-4 py-3.5 rounded-lg text-sm font-medium transition-colors duration-150 cursor-pointer min-h-[44px]',
                item.danger
                  ? 'text-red-500 hover:bg-red-500/10'
                  : 'text-fg hover:bg-surface-2'
              )}
            >
              {item.icon && <item.icon className="w-[18px] h-[18px] flex-shrink-0" aria-hidden />}
              <span>{item.label}</span>
            </button>
          ))}
        </div>
        {/* 取消按钮 */}
        <div className="px-2 pb-2 pt-1 border-t border-border">
          <button
            onClick={handleClose}
            className="w-full flex items-center justify-center gap-2 px-4 py-3.5 rounded-lg text-sm font-medium text-muted hover:bg-surface-2 transition-colors duration-150 cursor-pointer min-h-[44px]"
          >
            <X className="w-[18px] h-[18px]" aria-hidden />
            <span>取消</span>
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}