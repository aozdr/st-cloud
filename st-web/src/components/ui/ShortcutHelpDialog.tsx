import { X } from 'lucide-react';

interface Props {
  open: boolean;
  onClose: () => void;
}

const SHORTCUTS: { keys: string; desc: string }[] = [
  { keys: 'Ctrl + K', desc: '聚焦搜索框' },
  { keys: 'Ctrl + A', desc: '全选当前页文件' },
  { keys: 'Ctrl + C', desc: '复制选中文件' },
  { keys: 'Ctrl + X', desc: '剪切选中文件' },
  { keys: 'Ctrl + V', desc: '粘贴到当前文件夹' },
  { keys: 'Delete', desc: '删除选中文件' },
  { keys: 'F2', desc: '重命名选中文件' },
  { keys: 'Enter', desc: '打开文件或进入文件夹' },
  { keys: '↑ ↓', desc: '上下移动焦点' },
  { keys: '← →', desc: '左右移动焦点' },
  { keys: 'Esc', desc: '取消选择' },
  { keys: '?', desc: '显示/隐藏快捷键面板' },
];

export default function ShortcutHelpDialog({ open, onClose }: Props) {
  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-content w-[420px] max-w-[92vw]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <h3 className="text-base font-semibold text-fg">键盘快捷键</h3>
          <button onClick={onClose} aria-label="关闭" className="text-muted hover:text-fg cursor-pointer">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
        <div className="px-5 py-4 space-y-2">
          {SHORTCUTS.map((s) => (
            <div key={s.keys} className="flex items-center justify-between py-1">
              <span className="text-sm text-muted">{s.desc}</span>
              <kbd className="px-2 py-0.5 text-xs font-medium text-muted bg-surface-2 border border-border rounded">
                {s.keys}
              </kbd>
            </div>
          ))}
        </div>
        <div className="px-5 py-3 border-t border-border text-center">
          <span className="text-xs text-muted">在文件列表区域使用快捷键</span>
        </div>
      </div>
    </div>
  );
}