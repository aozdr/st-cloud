import { useEffect } from 'react';
import { ClipboardPaste, FolderPlus, RefreshCw, CheckSquare, Upload } from 'lucide-react';

interface Props {
  x: number;
  y: number;
  hasClipboard: boolean;
  onAction: (action: string) => void;
  onClose: () => void;
}

export default function BlankContextMenu({ x, y, hasClipboard, onAction, onClose }: Props) {
  useEffect(() => {
    const handler = () => onClose();
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [onClose]);

  const adjustedX = Math.min(x, window.innerWidth - 200);
  const adjustedY = Math.min(y, window.innerHeight - 290);

  const items: Array<{ action: string; label: string; icon: any; disabled?: boolean }> = [
    { action: 'newFolder', label: '新建文件夹', icon: FolderPlus },
    { action: 'upload', label: '上传文件', icon: Upload },
    { action: 'paste', label: '粘贴', icon: ClipboardPaste, disabled: !hasClipboard },
    { action: 'refresh', label: '刷新', icon: RefreshCw },
    { action: 'selectAll', label: '全选', icon: CheckSquare },
  ];

  return (
    <div
      className="fixed z-50 w-48 bg-white rounded-lg shadow-md border border-stone-200 py-1.5 animate-scale-in"
      style={{ left: adjustedX, top: adjustedY }}
      onClick={(e) => e.stopPropagation()}
    >
      {items.map((item, idx) => {
        const Icon = item.icon;
        return (
          <button
            key={idx}
            disabled={item.disabled}
            onClick={() => {
              if (!item.disabled) {
                onAction(item.action);
                onClose();
              }
            }}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 text-sm transition-colors ${
              item.disabled
                ? 'text-stone-300 cursor-not-allowed'
                : 'text-stone-700 hover:bg-stone-50 cursor-pointer'
            }`}
          >
            <Icon className="w-4 h-4" />
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>
  );
}
