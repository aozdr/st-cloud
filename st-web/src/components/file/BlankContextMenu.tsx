import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ClipboardPaste, FolderPlus, RefreshCw, CheckSquare, Upload, type LucideIcon } from 'lucide-react';
import { usePermission } from '../../lib/permission';

interface Props {
  x: number;
  y: number;
  hasClipboard: boolean;
  onAction: (action: string) => void;
  onClose: () => void;
}

export default function BlankContextMenu({ x, y, hasClipboard, onAction, onClose }: Props) {
  const { has } = usePermission();
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: x, top: y });

  useEffect(() => {
    const clickHandler = () => onClose();
    document.addEventListener('click', clickHandler);
    return () => document.removeEventListener('click', clickHandler);
  }, [onClose]);

  useLayoutEffect(() => {
    const el = ref.current;
    const w = el?.offsetWidth ?? 192;
    const h = el?.offsetHeight ?? 290;
    let left = x;
    let top = y;
    if (left + w > window.innerWidth) left = window.innerWidth - w - 4;
    if (top + h > window.innerHeight) top = y - h;
    if (top < 0) top = 4;
    setPos({ left, top });
  }, [x, y]);

  const items: Array<{ action: string; label: string; icon: LucideIcon; disabled?: boolean }> = [
    ...(has('file:upload') ? [{ action: 'newFolder', label: '新建文件夹', icon: FolderPlus }] : []),
    ...(has('file:upload') ? [{ action: 'upload', label: '上传文件', icon: Upload }] : []),
    { action: 'paste', label: '粘贴', icon: ClipboardPaste, disabled: !hasClipboard || (!has('file:copy') && !has('file:move')) },
    { action: 'refresh', label: '刷新', icon: RefreshCw },
    { action: 'selectAll', label: '全选', icon: CheckSquare },
  ];

  return createPortal(
    <div
      ref={ref}
      className="fixed z-[100] w-48 bg-surface rounded-lg shadow-md border border-border py-1.5 animate-scale-in"
      style={{ left: pos.left, top: pos.top }}
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
                ? 'text-muted cursor-not-allowed'
                : 'text-fg hover:bg-surface-2 cursor-pointer'
            }`}
          >
            <Icon className="w-4 h-4" aria-hidden />
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>,
    document.body,
  );
}