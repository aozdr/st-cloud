import { useEffect, useRef } from 'react';
import { Download, Pencil, FolderInput, Copy, Trash2, FolderOpen, Eye, Scissors, ClipboardPaste, Share2, History, Info, Star, type LucideIcon } from 'lucide-react';
import type { FileNode } from '../../types';
import { usePermission } from '../../lib/permission';

interface Props {
  x: number;
  y: number;
  node: FileNode;
  hasClipboard: boolean;
  showShare?: boolean;
  showVersions?: boolean;
  isFav?: boolean;
  onAction: (action: string, node: FileNode) => void;
  onClose: () => void;
}

export default function ContextMenu({ x, y, node, hasClipboard, showShare = true, showVersions = true, isFav = false, onAction, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const { has } = usePermission();

  useEffect(() => {
    const handler = () => onClose();
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [onClose]);

  // Adjust position to stay within viewport
  const adjustedX = Math.min(x, window.innerWidth - 200);
  const adjustedY = Math.min(y, window.innerHeight - 360);

  const rawItems: Array<{ action?: string; label?: string; icon?: LucideIcon; danger?: boolean; type?: 'separator' }> = [
    ...(node.nodeType === 0
      ? [{ action: 'open', label: '打开', icon: FolderOpen }]
      : has('file:preview')
        ? [{ action: 'preview', label: '预览', icon: Eye }]
        : []),
    { action: 'details', label: '详情', icon: Info },
    { action: 'favorite', label: isFav ? '取消收藏' : '收藏', icon: Star },
    ...(has('file:move') ? [{ action: 'cut', label: '剪切', icon: Scissors }] : []),
    ...(has('file:copy') ? [{ action: 'copy', label: '复制', icon: Copy }] : []),
    ...(hasClipboard && (has('file:copy') || has('file:move')) ? [{ action: 'paste', label: '粘贴', icon: ClipboardPaste }] : []),
    { type: 'separator' as const },
    ...(node.nodeType === 1 && has('file:download') ? [{ action: 'download', label: '下载', icon: Download }] : []),
    ...(has('file:rename') ? [{ action: 'rename', label: '重命名', icon: Pencil }] : []),
    ...(has('file:move') ? [{ action: 'moveTo', label: '移动到…', icon: FolderInput }] : []),
    ...(node.nodeType === 1 && showVersions ? [{ action: 'versions', label: '历史版本', icon: History }] : []),
    { type: 'separator' as const },
    ...(showShare && has('file:share') ? [{ action: 'share', label: '分享', icon: Share2 }] : []),
    { type: 'separator' as const },
    ...(has('file:delete') ? [{ action: 'delete', label: '删除', icon: Trash2, danger: true }] : []),
  ];
  // 折叠连续分隔符、去除首尾分隔符
  const items = rawItems.filter((item, idx, arr) => {
    if (item.type === 'separator') {
      const prev = arr[idx - 1];
      const next = arr[idx + 1];
      if (!prev || !next || prev.type === 'separator' || next.type === 'separator') return false;
    }
    return true;
  });

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
        const Icon = item.icon!;
        return (
          <button
            key={idx}
            onClick={() => onAction(item.action!, node)}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 text-sm cursor-pointer transition-colors ${
              item.danger ? 'text-red-600 hover:bg-red-50' : 'text-stone-700 hover:bg-stone-50'
            }`}
          >
            <Icon className="w-4 h-4" aria-hidden />
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>
  );
}
