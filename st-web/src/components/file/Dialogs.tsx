import { useState, useEffect } from 'react';
import type { FileNode } from '../../types';
import { X, FolderPlus, FolderOpen, Pencil } from 'lucide-react';
import { getFileTypeConfig, cn } from '../../lib/utils';

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

  if (!open) return null;

  const handleCreate = async () => {
    if (!name.trim()) return;
    setLoading(true);
    try {
      await onCreate(parentId, name.trim());
      onSuccess();
    } catch (err) {
      console.error('Create folder failed:', err);
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
    } catch (err) {
      console.error('Rename failed:', err);
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
      <div className="w-24 h-24 bg-stone-50 rounded-2xl flex items-center justify-center mb-4">
        <FolderOpen className="w-10 h-10 text-stone-300" />
      </div>
      <h3 className="text-base font-medium text-stone-900 mb-1">此文件夹为空</h3>
      <p className="text-sm text-stone-500 mb-4">上传文件或创建文件夹开始管理你的内容</p>
      <button onClick={onCreateFolder} className="btn-primary">
        <FolderPlus className="w-4 h-4" />
        <span>新建文件夹</span>
      </button>
    </div>
  );
}
