import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ClipboardPaste, FolderPlus, RefreshCw, CheckSquare, Upload, FilePlus2, ChevronRight, FileType, FileText, FileSpreadsheet, Presentation, type LucideIcon } from 'lucide-react';
import { usePermission } from '../../lib/permission';
import type { BlankFileType } from '../../types';

interface Props {
  x: number;
  y: number;
  hasClipboard: boolean;
  onAction: (action: string) => void;
  /** 新建空白文件：txt/docx/xlsx/pptx（成功后 Office 类型由 FileBrowser 决定跳转编辑） */
  onNewFile: (type: BlankFileType) => void;
  onClose: () => void;
}

/** 「新建」子菜单内容：新建文件夹 + 4 种空白文件类型（D2，与工具栏「新建」下拉一致） */
const NEW_ITEMS: Array<{ type: 'folder' } | { type: 'file'; fileType: BlankFileType; label: string; icon: LucideIcon }> = [
  { type: 'folder' },
  { type: 'file', fileType: 'txt', label: '文本文档', icon: FileType },
  { type: 'file', fileType: 'docx', label: 'Word 文档', icon: FileText },
  { type: 'file', fileType: 'xlsx', label: 'Excel 表格', icon: FileSpreadsheet },
  { type: 'file', fileType: 'pptx', label: 'PPT 演示', icon: Presentation },
];

export default function BlankContextMenu({ x, y, hasClipboard, onAction, onNewFile, onClose }: Props) {
  const { has } = usePermission();
  const ref = useRef<HTMLDivElement>(null);
  const newItemRef = useRef<HTMLButtonElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: x, top: y });
  /** 「新建」飞出子菜单的定位（基于菜单项坐标，避免溢出视口） */
  const [newSubmenu, setNewSubmenu] = useState<{ left: number; top: number } | null>(null);

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

  /** 打开/关闭「新建」子菜单：位置跟随「新建」菜单项；靠近右/下边缘时自动翻转 */
  const toggleNewSubmenu = () => {
    if (newSubmenu) {
      setNewSubmenu(null);
      return;
    }
    const rect = newItemRef.current?.getBoundingClientRect();
    const w = 176; // w-44
    const h = 5 * 36 + 10; // 5 项 + 分隔线近似高度
    let left = (rect?.right ?? pos.left + 192) + 4;
    let top = (rect?.top ?? pos.top) - 4;
    if (left + w > window.innerWidth) left = pos.left - w - 4;
    if (top + h > window.innerHeight) top = window.innerHeight - h - 4;
    if (top < 0) top = 4;
    setNewSubmenu({ left, top });
  };

  const items: Array<{ action: string; label: string; icon: LucideIcon; disabled?: boolean; submenu?: boolean }> = [
    ...(has('file:upload') ? [{ action: 'new', label: '新建', icon: FilePlus2, submenu: true }] : []),
    ...(has('file:upload') ? [{ action: 'upload', label: '上传文件', icon: Upload }] : []),
    { action: 'paste', label: '粘贴', icon: ClipboardPaste, disabled: !hasClipboard || (!has('file:copy') && !has('file:move')) },
    { action: 'refresh', label: '刷新', icon: RefreshCw },
    { action: 'selectAll', label: '全选', icon: CheckSquare },
  ];

  return (
    <>
      {createPortal(
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
                ref={item.submenu ? newItemRef : undefined}
                disabled={item.disabled}
                onClick={() => {
                  if (item.disabled) return;
                  if (item.submenu) {
                    toggleNewSubmenu();
                    return;
                  }
                  onAction(item.action);
                  onClose();
                }}
                onMouseEnter={() => {
                  if (item.submenu && !newSubmenu) toggleNewSubmenu();
                }}
                className={`w-full flex items-center gap-2.5 px-3 py-1.5 text-sm transition-colors ${
                  item.disabled
                    ? 'text-muted cursor-not-allowed'
                    : 'text-fg hover:bg-surface-2 cursor-pointer'
                }`}
              >
                <Icon className="w-4 h-4" aria-hidden />
                <span>{item.label}</span>
                {item.submenu && <ChevronRight className="w-4 h-4 ml-auto" aria-hidden />}
              </button>
            );
          })}
        </div>,
        document.body,
      )}
      {newSubmenu && createPortal(
        <div
          className="fixed z-[110] w-44 bg-surface rounded-lg shadow-md border border-border py-1.5 animate-scale-in"
          style={{ left: newSubmenu.left, top: newSubmenu.top }}
          onClick={(e) => e.stopPropagation()}
        >
          {NEW_ITEMS.map((item, idx) => {
            if (item.type === 'folder') {
              return (
                <div key={idx}>
                  <button
                    onClick={() => { onAction('newFolder'); onClose(); }}
                    className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 cursor-pointer transition-colors"
                  >
                    <FolderPlus className="w-4 h-4" aria-hidden />
                    <span>新建文件夹</span>
                  </button>
                  <div className="my-1 border-t border-border" />
                </div>
              );
            }
            const Icon = item.icon;
            return (
              <button
                key={idx}
                onClick={() => { onNewFile(item.fileType); onClose(); }}
                className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 cursor-pointer transition-colors"
              >
                <Icon className="w-4 h-4" aria-hidden />
                <span>{item.label}</span>
              </button>
            );
          })}
        </div>,
        document.body,
      )}
    </>
  );
}
