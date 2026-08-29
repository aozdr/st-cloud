import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Download, Pencil, FolderInput, Copy, Trash2, FolderOpen, Eye, Edit3, Scissors, ClipboardPaste, Share2, History, Info, Star, EyeOff, Lock, Unlock, FileInput, FileOutput, FileText, Archive, RefreshCw, type LucideIcon } from 'lucide-react';
import type { FileNode } from '../../types';
import { usePermission } from '../../lib/permission';
import { isText, isZip } from '../../lib/utils';
import { isEditableOfficeSuffix } from '../../lib/editor';

interface Props {
  x: number;
  y: number;
  node: FileNode;
  hasClipboard: boolean;
  showShare?: boolean;
  showVersions?: boolean;
  /** 显示「在线编辑」入口：docx/xlsx/pptx/pdf 且有编辑权限（由 FileBrowser 计算） */
  showEdit?: boolean;
  /** 显示「转换为 PDF/Word」入口（Word/PDF 文件且有上传权限，由 FileBrowser 传入） */
  showConvert?: boolean;
  /** 显示「编辑」入口（文本类文件且有上传权限，由 FileBrowser 传入） */
  showTextEdit?: boolean;
  /** 显示「在线解压」入口（压缩包文件且有上传权限，由 FileBrowser 传入） */
  showArchive?: boolean;
  isFav?: boolean;
  /** 团队空间：启用右键锁定/解锁入口（按节点锁定状态展示「锁定/解锁」其一） */
  lockable?: boolean;
  /** 节点当前锁定状态（仅 lockable 时生效；由 FileBrowser 按节点后端锁定字段计算） */
  locked?: boolean;
  onAction: (action: string, node: FileNode) => void;
  onClose: () => void;
}

export default function ContextMenu({ x, y, node, hasClipboard, showShare = true, showVersions = true, showEdit = false, showConvert = false, showTextEdit = false, showArchive = false, isFav = false, lockable = false, locked = false, onAction, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const { has } = usePermission();
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: x, top: y });

  // Word/PDF 文件且具备上传权限时显示转换入口（doc/docx -> PDF；pdf -> Word）
  const suffix = (node.suffix || '').toLowerCase();
  const convertItem: Array<{ action: string; label: string; icon: LucideIcon }> =
    showConvert && node.nodeType === 1 && has('file:upload')
      ? suffix === 'pdf'
        ? [{ action: 'convert', label: '转换为 Word', icon: FileInput }]
        : suffix === 'doc' || suffix === 'docx'
          ? [{ action: 'convert', label: '转换为 PDF', icon: FileOutput }]
          : []
      : [];
  // 文本类文件（txt/md/代码等）提供应用内编辑入口
  const textEditItem: Array<{ action: string; label: string; icon: LucideIcon }> =
    showTextEdit && node.nodeType === 1 && isText(node.suffix) && has('file:upload')
      ? [{ action: 'textEdit', label: '编辑', icon: FileText }]
      : [];
  // ZIP 压缩包提供在线解压入口（后端仅支持 zip；解压会写入文件，需上传权限）
  const archiveItem: Array<{ action: string; label: string; icon: LucideIcon }> =
    showArchive && node.nodeType === 1 && isZip(node.suffix) && has('file:upload')
      ? [{ action: 'archive', label: '在线解压', icon: Archive }]
      : [];

  useEffect(() => {
    const clickHandler = () => onClose();
    document.addEventListener('click', clickHandler);
    return () => document.removeEventListener('click', clickHandler);
  }, [onClose]);

  // Position: cursor at top-left; 依视口边界与 Electron 标题栏安全区翻转/钳制，避免被顶部系统按钮遮挡或溢出窗口
  useLayoutEffect(() => {
    const el = ref.current;
    const w = el?.offsetWidth ?? 192;
    const h = el?.offsetHeight ?? 360;
    const margin = 6;
    const titlebarH = document.querySelector('.app-titlebar')?.getBoundingClientRect().height ?? 0;
    const safeTop = titlebarH + margin;
    const safeLeft = margin;
    const safeRight = window.innerWidth - margin;
    const safeBottom = window.innerHeight - margin;
    let left = x;
    let top = y;
    if (left + w > safeRight) left = safeRight - w;
    if (top + h > safeBottom) top = y - h;
    if (top < safeTop) top = safeTop;
    if (left < safeLeft) left = safeLeft;
    if (top + h > safeBottom) top = safeBottom - h;
    setPos({ left, top });
  }, [x, y]);

  const rawItems: Array<{ action?: string; label?: string; icon?: LucideIcon; danger?: boolean; type?: 'separator' }> = [
    ...(node.nodeType === 0
      ? [{ action: 'open', label: '打开', icon: FolderOpen }]
      : has('file:preview')
        ? [{ action: 'preview', label: '预览', icon: Eye }]
        : []),
    ...(node.nodeType === 1 && showEdit ? [{ action: 'edit', label: '在线编辑', icon: Edit3 }] : []),
    ...convertItem,
    ...textEditItem,
    ...archiveItem,
    { action: 'favorite', label: isFav ? '取消收藏' : '收藏', icon: Star },
    { action: 'hide', label: '隐藏', icon: EyeOff },
    ...(lockable ? [{ action: locked ? 'unlock' : 'lock', label: locked ? '解锁' : '锁定', icon: locked ? Unlock : Lock }] : []),
    ...(has('file:move') ? [{ action: 'cut', label: '剪切', icon: Scissors }] : []),
    ...(has('file:copy') ? [{ action: 'copy', label: '复制', icon: Copy }] : []),
    ...(hasClipboard && (has('file:copy') || has('file:move')) ? [{ action: 'paste', label: '粘贴', icon: ClipboardPaste }] : []),
    { type: 'separator' as const },
    // 文件走单文件下载，文件夹走 ZIP 打包下载（useFileDownload 已支持），均需下载权限
    ...(has('file:download') ? [{ action: 'download', label: '下载', icon: Download }] : []),
    ...(has('file:rename') ? [{ action: 'rename', label: '重命名', icon: Pencil }] : []),
    ...(has('file:move') ? [{ action: 'moveTo', label: '移动到…', icon: FolderInput }] : []),
    ...(node.nodeType === 1 && showVersions ? [{ action: 'versions', label: '历史版本', icon: History }] : []),
    { type: 'separator' as const },
    ...(showShare && has('file:share') ? [{ action: 'share', label: '分享', icon: Share2 }] : []),
    { action: 'details', label: '详情', icon: Info },
    // 重置编辑状态：常驻显示（仅支持在线编辑的文档类型）
    ...(node.nodeType === 1 && isEditableOfficeSuffix(node.suffix) && has('file:reset-editing')
      ? [{ action: 'resetEditing', label: '重置编辑状态', icon: RefreshCw }]
      : []),
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
      className="fixed z-[100] w-[180px] p-1.5 bg-surface rounded-[10px] shadow-card border border-border animate-scale-in overflow-y-auto"
      style={{ left: pos.left, top: pos.top, maxHeight: 'calc(100vh - 48px)' }}
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
            className={`w-full flex items-center gap-2.5 px-2.5 h-9 text-sm cursor-pointer rounded-[7px] transition-colors ${
              item.danger ? 'text-danger hover:bg-danger-light' : 'text-fg hover:bg-surface-2'
            }`}
          >
            <Icon className="w-4 h-4 flex-shrink-0" aria-hidden />
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>,
    document.body,
  );
}
