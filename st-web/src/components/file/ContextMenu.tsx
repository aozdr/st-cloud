import { useEffect, useRef } from 'react';
import { Download, Pencil, FolderInput, Copy, Trash2, FolderOpen, Eye, Scissors, ClipboardPaste, Share2, History } from 'lucide-react';
import type { FileNode } from '../../types';

interface Props {
  x: number;
  y: number;
  node: FileNode;
  hasClipboard: boolean;
  showShare?: boolean;
  showVersions?: boolean;
  onAction: (action: string, node: FileNode) => void;
  onClose: () => void;
}

export default function ContextMenu({ x, y, node, hasClipboard, showShare = true, showVersions = true, onAction, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = () => onClose();
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [onClose]);

  // Adjust position to stay within viewport
  const adjustedX = Math.min(x, window.innerWidth - 200);
  const adjustedY = Math.min(y, window.innerHeight - 360);

  const items: Array<{ action?: string; label?: string; icon?: any; danger?: boolean; type?: 'separator' }> = [
    ...(node.nodeType === 0
      ? [{ action: 'open', label: '打开', icon: FolderOpen }]
      : [{ action: 'preview', label: '预览', icon: Eye }]),
    { action: 'cut', label: '剪切', icon: Scissors },
    { action: 'copy', label: '复制', icon: Copy },
    ...(hasClipboard ? [{ action: 'paste', label: '粘贴', icon: ClipboardPaste }] : []),
    { type: 'separator' as const },
    ...(node.nodeType === 1 ? [{ action: 'download', label: '下载', icon: Download }] : []),
    { action: 'rename', label: '重命名', icon: Pencil },
    { action: 'moveTo', label: '移动到…', icon: FolderInput },
    ...(node.nodeType === 1 && showVersions ? [{ action: 'versions', label: '历史版本', icon: History }] : []),
    { type: 'separator' as const },
    ...(showShare ? [{ action: 'share', label: '分享', icon: Share2 }] : []),
    { type: 'separator' as const },
    { action: 'delete', label: '删除', icon: Trash2, danger: true },
  ];

  return (
    <div
      ref={ref}
      className="fixed z-50 w-48 bg-white rounded-lg shadow-md border border-stone-200 py-1.5 animate-scale-in"
      style={{ left: adjustedX, top: adjustedY }}
      onClick={(e) => e.stopPropagation()}
    >
      {items.map((item, idx) => {
        if (item.type === 'separator') {
          return <div key={idx} className="my-1 border-t border-stone-100" />;
        }
        const Icon = item.icon;
        return (
          <button
            key={idx}
            onClick={() => onAction(item.action!, node)}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 text-sm cursor-pointer transition-colors ${
              item.danger ? 'text-red-600 hover:bg-red-50' : 'text-stone-700 hover:bg-stone-50'
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
