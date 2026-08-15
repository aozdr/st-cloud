import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Download, Pencil, FolderInput, Copy, Trash2, FolderOpen, Eye, Edit3, Scissors, ClipboardPaste, Share2, History, Info, Star, EyeOff, Lock, Unlock, type LucideIcon } from 'lucide-react';
import type { FileNode } from '../../types';
import { usePermission } from '../../lib/permission';

interface Props {
  x: number;
  y: number;
  node: FileNode;
  hasClipboard: boolean;
  showShare?: boolean;
  showVersions?: boolean;
  /** 显示「在线编辑」入口：docx/xlsx/pptx 且有编辑权限（由 FileBrowser 计算） */
  showEdit?: boolean;
  isFav?: boolean;
  /** 团队空间：启用右键锁定/解锁入口（按节点锁定状态展示「锁定/解锁」其一） */
  lockable?: boolean;
  /** 节点当前锁定状态（仅 lockable 时生效；由 FileBrowser 按节点后端锁定字段计算） */
  locked?: boolean;
  onAction: (action: string, node: FileNode) => void;
  onClose: () => void;
}

export default function ContextMenu({ x, y, node, hasClipboard, showShare = true, showVersions = true, showEdit = false, isFav = false, lockable = false, locked = false, onAction, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const { has } = usePermission();
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: x, top: y });

  useEffect(() => {
    const clickHandler = () => onClose();
    document.addEventListener('click', clickHandler);
    return () => document.removeEventListener('click', clickHandler);
  }, [onClose]);

  // Position: cursor at top-left; if not enough space below, flip so cursor is at bottom-left
  useLayoutEffect(() => {
    const el = ref.current;
    const w = el?.offsetWidth ?? 192;
    const h = el?.offsetHeight ?? 360;
    let left = x;
    let top = y;
    if (left + w > window.innerWidth) left = window.innerWidth - w - 4;
    if (top + h > window.innerHeight) top = y - h;
    if (top < 0) top = 4;
    setPos({ left, top });
  }, [x, y]);

  const rawItems: Array<{ action?: string; label?: string; icon?: LucideIcon; danger?: boolean; type?: 'separator' }> = [
    ...(node.nodeType === 0
      ? [{ action: 'open', label: '打开', icon: FolderOpen }]
      : has('file:preview')
        ? [{ action: 'preview', label: '预览', icon: Eye }]
        : []),
    ...(node.nodeType === 1 && showEdit ? [{ action: 'edit', label: '在线编辑', icon: Edit3 }] : []),
    { action: 'details', label: '详情', icon: Info },
    { action: 'favorite', label: isFav ? '取消收藏' : '收藏', icon: Star },
    { action: 'hide', label: '隐藏', icon: EyeOff },
    ...(lockable ? [{ action: locked ? 'unlock' : 'lock', label: locked ? '解锁' : '锁定', icon: locked ? Unlock : Lock }] : []),
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
  const items = rawItems.filter((item, idx, arr) => {
    if (item.type === 'separator') {
      const prev = arr[idx - 1];
      const next = arr[idx + 1];
      if (!prev || !next || prev.type === 'separator' || next.type === 'separator') return false;
    }
    return true;
  });

  return createPortal(
    <div
      ref={ref}
      className="fixed z-[100] w-48 bg-surface rounded-lg shadow-md border border-border py-1.5 animate-scale-in"
      style={{ left: pos.left, top: pos.top }}
      onClick={(e) => e.stopPropagation()}
    >
      {items.map((item, idx) => {
        if (item.type === 'separator') {
          return <div key={idx} className="my-1 border-t border-border" />;
        }
        const Icon = item.icon!;
        return (
          <button
            key={idx}
            onClick={() => onAction(item.action!, node)}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 text-sm cursor-pointer transition-colors ${
              item.danger ? 'text-red-500 hover:bg-red-500/10' : 'text-fg hover:bg-surface-2'
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
