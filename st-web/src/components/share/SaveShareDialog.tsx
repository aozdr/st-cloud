import { useState, useEffect } from 'react';
import type { FileTreeNode, ShareSaveVO } from '../../types';
import api from '../../lib/api';
import { X, FolderClosed, FolderOpen, ChevronRight, Save } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useToast } from '../ui/Toast';

interface Props {
  shareCode: string;
  password?: string;
  sourceName: string;
  isFolder: boolean;
  onClose: () => void;
  onSaved: (vo: ShareSaveVO, targetParentId: string) => void;
}

/**
 * 保存分享内容到云盘：用户选择目标文件夹后调用 /share/save。
 * 仅携带分享码 + 目标文件夹，后端只保存分享边界内的文件，绝不越界。
 */
export default function SaveShareDialog({ shareCode, password, sourceName, isFolder, onClose, onSaved }: Props) {
  const [tree, setTree] = useState<FileTreeNode[]>([]);
  const [targetId, setTargetId] = useState('0');
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['0']));
  const [loading, setLoading] = useState(false);
  const { showToast } = useToast();

  useEffect(() => {
    api.get<FileTreeNode[]>('/file/tree').then(setTree).catch(() => {});
  }, []);

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleSave = async () => {
    setLoading(true);
    try {
      const vo: ShareSaveVO = await api.post('/share/save', {
        shareCode,
        password: password || undefined,
        targetParentId: targetId,
      });
      onSaved(vo, targetId);
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '保存失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  const renderTree = (nodes: FileTreeNode[], level: number): React.ReactNode => {
    return nodes.map((node) => (
      <div key={node.id}>
        <div
          className={cn(
            'flex items-center gap-1.5 px-2 py-1.5 rounded-md cursor-pointer transition-colors text-sm',
            targetId === node.id ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted',
          )}
          style={{ paddingLeft: `${level * 20 + 8}px` }}
          onClick={() => setTargetId(node.id)}
        >
          {node.children.length > 0 ? (
            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleExpand(node.id);
              }}
              className="p-0.5 hover:bg-surface-2 rounded cursor-pointer"
              aria-label="展开或折叠"
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div className="w-full max-w-md bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
              <Save className="w-4 h-4 text-white" />
            </div>
            <h2 className="text-base font-semibold text-fg">保存到云盘</h2>
          </div>
          <button onClick={onClose} className="text-muted hover:text-fg transition-colors cursor-pointer" aria-label="关闭">
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        <div className="px-5 py-4 space-y-3">
          <div className="text-sm text-muted truncate">
            将<span className="font-medium text-fg">{sourceName}</span>
            {isFolder ? '（文件夹及其全部内容）' : ''}保存到：
          </div>

          <div className="border border-border rounded-lg max-h-56 overflow-y-auto py-2">
            <div
              className={cn(
                'flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer transition-colors text-sm',
                targetId === '0' ? 'bg-primary-500/10 text-primary-600 font-medium' : 'hover:bg-surface-2 text-muted',
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
        </div>

        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleSave} disabled={loading} className="btn-primary">
            {loading ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
