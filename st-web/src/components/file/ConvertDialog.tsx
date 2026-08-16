import { useEffect, useState } from 'react';
import { X, FileInput, FileOutput, Loader2 } from 'lucide-react';
import api from '../../lib/api';
import type { FileNode } from '../../types';
import { useToast } from '../ui/Toast';

/** 默认转换文件名：原文件名（去后缀）-转换.目标后缀 */
function defaultTargetName(node: FileNode): string {
  const base = node.name.replace(/\.[^.]+$/, '');
  const ext = node.suffix?.toLowerCase() === 'pdf' ? 'docx' : 'pdf';
  return `${base}-转换.${ext}`;
}

interface Props {
  node: FileNode | null;
  onClose: () => void;
  onConverted: () => void;
}

/** Word<->PDF 转换对话框：默认文件名「原文件名-转换」，可编辑；重名由后端按云盘规则自动追加序号 */
export default function ConvertDialog({ node, onClose, onConverted }: Props) {
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const { showToast } = useToast();

  useEffect(() => {
    if (node) setName(defaultTargetName(node));
  }, [node]);

  if (!node) return null;

  const toPdf = node.suffix?.toLowerCase() !== 'pdf';
  const title = toPdf ? '转换为 PDF' : '转换为 Word';
  const Icon = toPdf ? FileOutput : FileInput;

  const handleConvert = async () => {
    if (!name.trim() || loading) return;
    setLoading(true);
    try {
      await api.post(`/file/${node.id}/convert`, { fileName: name.trim() });
      showToast('转换成功');
      onConverted();
      onClose();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '转换失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-96" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
          <div className="flex items-center gap-2">
            <Icon className="w-4 h-4 text-primary-600" />
            <h3 className="text-base font-semibold text-fg">{title}</h3>
          </div>
          <button
            onClick={onClose}
            aria-label="关闭"
            className="text-muted hover:text-fg cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
        <div className="px-5 py-4">
          <p className="text-sm text-muted mb-2">当前文件: {node.name}</p>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleConvert()}
            className="input-field"
            placeholder="转换后的文件名"
          />
          <p className="mt-2 text-xs text-muted">同名文件将按云盘规则自动追加序号</p>
        </div>
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleConvert} disabled={loading || !name.trim()} className="btn-primary">
            {loading ? (
              <span className="flex items-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" aria-hidden />
                转换中…
              </span>
            ) : '转换'}
          </button>
        </div>
      </div>
    </div>
  );
}
