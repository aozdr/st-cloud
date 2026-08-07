import { useState, useEffect } from 'react';
import type { FileNode } from '../../types';
import { X, FolderPlus, FolderOpen, Pencil } from 'lucide-react';
import { getFileTypeConfig, cn } from '../../lib/utils';
import { useToast } from '../ui/Toast';

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
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-stone-100">
          <div className="flex items-center gap-2">
            <FolderPlus className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-stone-900">新建文件夹</h3>
          </div>
          <button onClick={onClose} className="text-stone-400 hover:text-stone-900 cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="px-5 py-4">
          <div className="relative">
            <FolderOpen className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-stone-400" />
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
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-stone-100">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleCreate} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? '创建中...' : '创建'}
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
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-stone-100">
          <div className="flex items-center gap-2">
            <Pencil className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-stone-900">重命名</h3>
          </div>
          <button onClick={onClose} className="text-stone-400 hover:text-stone-900 cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="px-5 py-4">
          <div className="flex items-center gap-2 mb-2">
            <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', config.bgColor)}>
              <Icon className={cn('w-4 h-4', config.color)} />
            </div>
            <span className="text-sm text-stone-500">当前名称: {node.name}</span>
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
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-stone-100">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleRename} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? '处理中...' : '确定'}
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
          <ellipse cx="48" cy="72" rx="36" ry="5" fill="#F5F5F4" />
          <path d="M20 16C20 14.9 20.9 14 22 14H42L50 22H74C75.1 22 76 22.9 76 24V60C76 61.1 75.1 62 74 62H22C20.9 62 20 61.1 20 60V16Z" fill="#FFF7F7" stroke="#FCA5A5" strokeWidth="1.5" />
          <path d="M20 28H76V58C76 59.1 75.1 60 74 60H22C20.9 60 20 59.1 20 58V28Z" fill="#FEE2E2" />
          <circle cx="40" cy="42" r="4" fill="#F87171" opacity="0.6" />
          <path d="M32 52L40 44L48 50L56 42L64 48V54C64 55.1 63.1 56 62 56H34C32.9 56 32 55.1 32 54V52Z" fill="#F87171" opacity="0.5" />
          <path d="M60 18C60 17.4 60.4 17 61 17H72V28H60V18Z" fill="#FECACA" />
        </svg>
        <div className="absolute -top-1 -right-2 w-6 h-6 rounded-full bg-primary-100 flex items-center justify-center">
          <FolderPlus className="w-3.5 h-3.5 text-primary-600" aria-hidden />
        </div>
      </div>
      <h3 className="text-base font-semibold text-stone-900 mb-1">此文件夹为空</h3>
      <p className="text-sm text-stone-500 mb-5">上传文件或创建文件夹开始管理你的内容</p>
      <button onClick={onCreateFolder} className="btn-primary">
        <FolderPlus className="w-4 h-4" aria-hidden />
        <span>新建文件夹</span>
      </button>
    </div>
  );
}


// ==================== File List Skeleton ====================
const SKELETON_WIDTHS = ['55%', '70%', '45%', '62%', '50%', '68%', '40%', '58%', '52%', '48%'];

export function FileListSkeleton({ view = 'list' }: { view?: 'list' | 'grid' }) {
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
    <div className="overflow-hidden rounded-xl bg-white">
      {Array.from({ length: 10 }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-2.5 border-b border-stone-50 last:border-0">
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