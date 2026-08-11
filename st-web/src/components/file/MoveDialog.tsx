import { useState, useEffect } from 'react';
import type { FileTreeNode } from '../../types';
import { X, FolderClosed, ChevronRight, FolderOpen } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useToast } from '../ui/Toast';

interface Props {
  nodeIds: string[];
  mode: 'move' | 'copy';
  loadTree: () => Promise<FileTreeNode[]>;
  onConfirm: (nodeIds: string[], targetId: string, mode: 'move' | 'copy') => Promise<void>;
  onClose: () => void;
  onSuccess: () => void;
}

export default function MoveDialog({ nodeIds, mode, loadTree, onConfirm, onClose, onSuccess }: Props) {
  const [tree, setTree] = useState<FileTreeNode[]>([]);
  const { showToast } = useToast();
  const [targetId, setTargetId] = useState('0');
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['0']));
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadTree().then(setTree).catch(() => {});
  }, []);

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) { next.delete(id); } else { next.add(id); }
      return next;
    });
  };

  const handleConfirm = async () => {
    setLoading(true);
    try {
      await onConfirm(nodeIds, targetId, mode);
      onSuccess();
    } catch {
      showToast('\u64cd\u4f5c\u5931\u8d25', 'error');
    } finally {
      setLoading(false);
    }
  };

  const renderTree = (nodes: FileTreeNode[], level: number): React.ReactNode => {
    return nodes.map((node) => (
      <div key={node.id}>
        <div
          className={cn(
            'flex items-center gap-1.5 px-2 py-1.5 rounded-lg cursor-pointer transition-colors text-sm',
            targetId === node.id ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted'
          )}
          style={{ paddingLeft: `${level * 20 + 8}px` }}
          onClick={() => setTargetId(node.id)}
        >
          {node.children.length > 0 ? (
            <button
              onClick={(e) => { e.stopPropagation(); toggleExpand(node.id); }}
              className="p-0.5 hover:bg-surface-2 rounded cursor-pointer" aria-label="展开或折叠"
            >
              <ChevronRight className={cn('w-3.5 h-3.5 transition-transform', expanded.has(node.id) && 'rotate-90')} aria-hidden />
            </button>
          ) : (
            <span className="w-4" />
          )}
          {targetId === node.id ? (
            <FolderOpen className="w-4 h-4 text-primary-600 flex-shrink-0" />
          ) : (
            <FolderClosed className="w-4 h-4 text-amber-500 flex-shrink-0" />
          )}
          <span className="truncate">{node.name}</span>
        </div>
        {expanded.has(node.id) && node.children.length > 0 && renderTree(node.children, level + 1)}
      </div>
    ));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-96 max-h-[70vh] flex flex-col" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <h3 className="text-base font-semibold text-fg">
            {mode === 'move' ? '移动到' : '复制到'}
          </h3>
          <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭">
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>

        <div className="px-3 py-2 overflow-y-auto flex-1">
          {/* Root folder option */}
          <div
            className={cn(
              'flex items-center gap-2 px-2 py-1.5 rounded-lg cursor-pointer transition-colors text-sm',
              targetId === '0' ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted'
            )}
            onClick={() => setTargetId('0')}
          >
            {targetId === '0' ? (
              <FolderOpen className="w-4 h-4 text-primary-600 flex-shrink-0" />
            ) : (
              <FolderClosed className="w-4 h-4 text-amber-500 flex-shrink-0" />
            )}
            <span>全部文件</span>
          </div>
          {renderTree(tree, 0)}
        </div>

        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleConfirm} disabled={loading} className="btn-primary">
            {loading ? '处理中…' : '确定'}
          </button>
        </div>
      </div>
    </div>
  );
}
