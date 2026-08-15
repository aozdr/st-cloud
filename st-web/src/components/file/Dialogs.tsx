import { useState, useEffect, useRef } from 'react';
import type { BlankFileType, FileNode } from '../../types';
import { X, FolderPlus, FolderOpen, Pencil, FilePlus } from 'lucide-react';
import { getFileTypeConfig, cn } from '../../lib/utils';
import { useToast } from '../ui/Toast';

/** 各类型默认文件名（与后端 DEFAULT_NAMES 一致；用户可修改） */
const DEFAULT_FILE_NAMES: Record<BlankFileType, string> = {
  txt: '新建文本文档.txt',
  docx: '新建文档.docx',
  xlsx: '新建表格.xlsx',
  pptx: '新建演示.pptx',
};

const FILE_TYPE_LABELS: Record<BlankFileType, string> = {
  txt: '文本文档',
  docx: 'Word 文档',
  xlsx: 'Excel 表格',
  pptx: 'PPT 演示',
};

// ==================== Create File Dialog ====================
/** 新建空白文件：弹出文件名输入框（预填默认名），确认后创建 */
export function CreateFileDialog({ open, type, onCreate, onClose }: {
  open: boolean;
  type: BlankFileType | null;
  onCreate: (type: BlankFileType, name: string) => Promise<void>;
  onClose: () => void;
}) {
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  // 打开后待全选标记：默认文件名提交后执行一次全选，便于用户直接输入覆盖
  const selectPendingRef = useRef(false);
  const { showToast } = useToast();

  useEffect(() => {
    if (open && type) {
      setName(DEFAULT_FILE_NAMES[type]);
      selectPendingRef.current = true;
    }
  }, [open, type]);

  useEffect(() => {
    if (selectPendingRef.current && inputRef.current) {
      const input = inputRef.current;
      // 只选中主文件名，保留 .后缀 不被选中（便于直接输入新文件名）
      const dot = name.lastIndexOf('.');
      const end = dot > 0 ? dot : name.length;
      input.setSelectionRange(0, end);
      selectPendingRef.current = false;
    }
  }, [name]);

  if (!open || !type) return null;

  const handleCreate = async () => {
    if (!name.trim()) return;
    setLoading(true);
    try {
      await onCreate(type, name.trim());
    } catch (err) {
      showToast(err instanceof Error ? err.message : '新建文件失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-96" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <div className="flex items-center gap-2">
            <FilePlus className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-fg">新建{FILE_TYPE_LABELS[type]}</h3>
          </div>
          <button onClick={onClose} aria-label="关闭" className="text-muted hover:text-fg cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
        <div className="px-5 py-4">
          <input
            ref={inputRef}
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            className="input-field"
            placeholder={`输入${FILE_TYPE_LABELS[type]}名称`}
          />
          <p className="mt-2 text-xs text-muted">留空或去掉后缀将自动使用默认名/补充后缀</p>
        </div>
        <div className="flex justify-end gap-2 px-5 py-3.5 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleCreate} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? '创建中…' : '创建'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ==================== Create Folder Dialog ====================
export function CreateFolderDialog({ open, parentId, onCreate, onClose, onSuccess }: {
  open: boolean;
  parentId: string;
  onCreate: (parentId: string, name: string) => Promise<void>;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open) setName('');
  }, [open]);
  const { showToast } = useToast();

  if (!open) return null;

  const handleCreate = async () => {
    if (!name.trim()) return;
    setLoading(true);
    try {
      await onCreate(parentId, name.trim());
      onSuccess();
    } catch {
      showToast('\u521b\u5efa\u6587\u4ef6\u5939\u5931\u8d25', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-96" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <div className="flex items-center gap-2">
            <FolderPlus className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-fg">新建文件夹</h3>
          </div>
          <button onClick={onClose} aria-label="关闭" className="text-muted hover:text-fg cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
        <div className="px-5 py-4">
          <div className="relative">
            <FolderOpen className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" />
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              className="input-field pl-10"
              placeholder="文件夹名称"
            />
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleCreate} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? '创建中…' : '创建'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ==================== Rename Dialog ====================
export function RenameDialog({ node, onRename, onClose, onSuccess }: {
  node: FileNode | null;
  onRename: (nodeId: string, newName: string) => Promise<void>;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (node) setName(node.name);
  }, [node]);
  const { showToast } = useToast();

  if (!node) return null;

  const handleRename = async () => {
    if (!name.trim() || name === node.name) {
      onClose();
      return;
    }
    setLoading(true);
    try {
      await onRename(node.id, name.trim());
      onSuccess();
    } catch {
      showToast('\u91cd\u547d\u540d\u5931\u8d25', 'error');
    } finally {
      setLoading(false);
    }
  };

  const config = getFileTypeConfig(node.nodeType, node.suffix);
  const Icon = config.icon;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-96" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <div className="flex items-center gap-2">
            <Pencil className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-fg">重命名</h3>
          </div>
          <button onClick={onClose} aria-label="关闭" className="text-muted hover:text-fg cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
        <div className="px-5 py-4">
          <div className="flex items-center gap-2 mb-2">
            <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', config.bgColor)}>
              <Icon className={cn('w-4 h-4', config.color)} />
            </div>
            <span className="text-sm text-muted">当前名称: {node.name}</span>
          </div>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleRename()}
            className="input-field"
            placeholder="新名称"
          />
        </div>
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleRename} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? '处理中…' : '确定'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ==================== Empty State ====================
export function EmptyState({ onCreateFolder }: { onCreateFolder: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center h-full py-20">
      <div className="relative mb-5">
        <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
          <ellipse cx="48" cy="72" rx="36" ry="5" fill="rgb(var(--color-primary-50))" />
          <path d="M20 16C20 14.9 20.9 14 22 14H42L50 22H74C75.1 22 76 22.9 76 24V60C76 61.1 75.1 62 74 62H22C20.9 62 20 61.1 20 60V16Z" fill="rgb(var(--color-primary-50))" stroke="rgb(var(--color-primary-300))" strokeWidth="1.5" />
          <path d="M20 28H76V58C76 59.1 75.1 60 74 60H22C20.9 60 20 59.1 20 58V28Z" fill="rgb(var(--color-primary-100))" />
          <circle cx="40" cy="42" r="4" fill="rgb(var(--color-primary-400))" opacity="0.6" />
          <path d="M32 52L40 44L48 50L56 42L64 48V54C64 55.1 63.1 56 62 56H34C32.9 56 32 55.1 32 54V52Z" fill="rgb(var(--color-primary-400))" opacity="0.5" />
          <path d="M60 18C60 17.4 60.4 17 61 17H72V28H60V18Z" fill="rgb(var(--color-primary-200))" />
        </svg>
        <div className="absolute -top-1 -right-2 w-6 h-6 rounded-full bg-primary-100 flex items-center justify-center">
          <FolderPlus className="w-3.5 h-3.5 text-primary-600" aria-hidden />
        </div>
      </div>
      <h3 className="text-base font-semibold text-fg mb-1">此文件夹为空</h3>
      <p className="text-sm text-muted mb-5">上传文件或创建文件夹开始管理你的内容</p>
      <button onClick={onCreateFolder} className="btn-primary">
        <FolderPlus className="w-4 h-4" aria-hidden />
        <span>新建文件夹</span>
      </button>
    </div>
  );
}


// ==================== File List Skeleton ====================
const SKELETON_WIDTHS = ['55%', '70%', '45%', '62%', '50%', '68%', '40%', '58%', '52%', '48%'];

export function FileListSkeleton({ view = 'table' }: { view?: 'table' | 'card' | 'grid' }) {
  if (view === 'grid') {
    return (
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-8 gap-3">
        {Array.from({ length: 16 }).map((_, i) => (
          <div key={i} className="flex flex-col rounded-lg p-3">
            <div className="h-20 mb-2 rounded-lg shimmer" />
            <div className="h-3 rounded shimmer mx-auto" style={{ width: SKELETON_WIDTHS[i % SKELETON_WIDTHS.length] }} />
          </div>
        ))}
      </div>
    );
  }
  return (
    <div className="overflow-hidden rounded-xl bg-surface">
      {Array.from({ length: 10 }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-2.5 border-b border-border last:border-0">
          <div className="w-[18px] h-[18px] rounded shimmer flex-shrink-0" />
          <div className="w-8 h-8 rounded-lg shimmer flex-shrink-0" />
          <div className="flex-1 h-4 rounded shimmer" style={{ maxWidth: SKELETON_WIDTHS[i % SKELETON_WIDTHS.length] }} />
          <div className="w-16 h-4 rounded shimmer flex-shrink-0" />
          <div className="w-20 h-4 rounded shimmer flex-shrink-0" />
          <div className="w-28 h-4 rounded shimmer flex-shrink-0" />
        </div>
      ))}
    </div>
  );
}
