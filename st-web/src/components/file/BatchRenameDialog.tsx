import { useState } from 'react';
import { X, Loader2 } from 'lucide-react';
import { useToast } from '../ui/Toast';
import api from '../../lib/api';
import type { FileNode } from '../../types';

interface Props {
  files: FileNode[];
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * 批量重命名对话框：
 * - 模式1：前缀+序号（如"照片_001、照片_002..."）
 * - 模式2：查找替换（将文件名中的关键词替换为新文本）
 * - 模式3：正则替换
 */
export default function BatchRenameDialog({ files, onClose, onSuccess }: Props) {
  const { showToast } = useToast();
  const [mode, setMode] = useState<'prefix' | 'replace' | 'regex'>('prefix');
  const [prefix, setPrefix] = useState('文件');
  const [startNum, setStartNum] = useState(1);
  const [padLen, setPadLen] = useState(2);
  const [findText, setFindText] = useState('');
  const [replaceText, setReplaceText] = useState('');
  const [regexPattern, setRegexPattern] = useState('');
  const [regexReplace, setRegexReplace] = useState('');
  const [loading, setLoading] = useState(false);

  /** 计算预览结果：返回前5个文件的新旧名称对照 */
  const getPreview = (): { old: string; newName: string }[] => {
    return files.slice(0, 5).map((file, idx) => {
      const ext = file.suffix ? '.' + file.suffix : '';
      const baseName = ext ? file.name.slice(0, -ext.length) : file.name;
      let newName = file.name;

      if (mode === 'prefix') {
        const num = String(startNum + idx).padStart(padLen, '0');
        newName = `${prefix}_${num}${ext}`;
      } else if (mode === 'replace') {
        newName = baseName.split(findText).join(replaceText) + ext;
      } else if (mode === 'regex') {
        try {
          const re = new RegExp(regexPattern, 'g');
          newName = baseName.replace(re, regexReplace) + ext;
        } catch {
          newName = file.name;
        }
      }
      return { old: file.name, newName };
    });
  };

  const handleConfirm = async () => {
    // 构建重命名计划
    const renamePlan = files.map((file, idx) => {
      const ext = file.suffix ? '.' + file.suffix : '';
      const baseName = ext ? file.name.slice(0, -ext.length) : file.name;
      let newName = file.name;

      if (mode === 'prefix') {
        const num = String(startNum + idx).padStart(padLen, '0');
        newName = `${prefix}_${num}${ext}`;
      } else if (mode === 'replace') {
        newName = baseName.split(findText).join(replaceText) + ext;
      } else if (mode === 'regex') {
        try {
          const re = new RegExp(regexPattern, 'g');
          newName = baseName.replace(re, regexReplace) + ext;
        } catch {
          newName = file.name;
        }
      }
      return { nodeId: file.id, newName };
    }).filter((p) => p.newName !== files.find((f) => f.id === p.nodeId)?.name);

    if (renamePlan.length === 0) {
      showToast('没有需要重命名的文件', 'warning');
      return;
    }

    setLoading(true);
    let successCount = 0;
    let failCount = 0;
    // 逐个调用重命名接口
    for (const item of renamePlan) {
      try {
        await api.put(`/file/${item.nodeId}/rename`, { newName: item.newName });
        successCount++;
      } catch {
        failCount++;
      }
    }
    setLoading(false);

    if (failCount === 0) {
      showToast(`成功重命名 ${successCount} 个文件`, 'success');
    } else {
      showToast(`成功 ${successCount} 个，失败 ${failCount} 个`, 'warning');
    }
    onSuccess();
    onClose();
  };

  const preview = getPreview();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <h2 className="text-base font-semibold text-fg">批量重命名（{files.length} 个文件）</h2>
          <button onClick={onClose} className="text-muted hover:text-fg" aria-label="关闭">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-5 space-y-4">
          {/* 模式切换 */}
          <div className="flex gap-2">
            {([
              { v: 'prefix', label: '前缀+序号' },
              { v: 'replace', label: '查找替换' },
              { v: 'regex', label: '正则替换' },
            ] as const).map((opt) => (
              <button
                key={opt.v}
                onClick={() => setMode(opt.v)}
                className={`flex-1 py-2 rounded-lg text-sm font-medium transition cursor-pointer ${
                  mode === opt.v
                    ? 'bg-primary-500/10 text-primary-600 ring-1 ring-primary-200'
                    : 'bg-surface-2 text-muted hover:text-fg'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>

          {/* 模式参数 */}
          {mode === 'prefix' && (
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">前缀名称</label>
                <input
                  type="text"
                  value={prefix}
                  onChange={(e) => setPrefix(e.target.value)}
                  className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                />
              </div>
              <div className="flex gap-3">
                <div className="flex-1">
                  <label className="text-xs font-medium text-muted mb-1.5 block">起始序号</label>
                  <input
                    type="number"
                    value={startNum}
                    onChange={(e) => setStartNum(Number(e.target.value) || 1)}
                    className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                  />
                </div>
                <div className="flex-1">
                  <label className="text-xs font-medium text-muted mb-1.5 block">补零位数</label>
                  <input
                    type="number"
                    value={padLen}
                    min={0}
                    max={6}
                    onChange={(e) => setPadLen(Number(e.target.value) || 0)}
                    className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                  />
                </div>
              </div>
            </div>
          )}

          {mode === 'replace' && (
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">查找</label>
                <input
                  type="text"
                  value={findText}
                  onChange={(e) => setFindText(e.target.value)}
                  placeholder="要替换的文本"
                  className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                />
              </div>
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">替换为</label>
                <input
                  type="text"
                  value={replaceText}
                  onChange={(e) => setReplaceText(e.target.value)}
                  placeholder="替换后的文本"
                  className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                />
              </div>
            </div>
          )}

          {mode === 'regex' && (
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">正则表达式</label>
                <input
                  type="text"
                  value={regexPattern}
                  onChange={(e) => setRegexPattern(e.target.value)}
                  placeholder="如 \d{4}"
                  className="w-full px-3 py-2 text-sm font-mono bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                />
              </div>
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">替换为</label>
                <input
                  type="text"
                  value={regexReplace}
                  onChange={(e) => setRegexReplace(e.target.value)}
                  placeholder="如 2024"
                  className="w-full px-3 py-2 text-sm bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface"
                />
              </div>
            </div>
          )}

          {/* 预览 */}
          <div className="bg-surface-2 rounded-lg p-3 space-y-1.5 max-h-40 overflow-auto">
            <div className="text-xs font-medium text-muted mb-1">预览（前 5 个）</div>
            {preview.map((item, idx) => (
              <div key={idx} className="flex items-center gap-2 text-xs">
                <span className="text-muted truncate flex-1">{item.old}</span>
                <span className="text-muted">→</span>
                <span className="text-primary-600 font-medium truncate flex-1">{item.newName}</span>
              </div>
            ))}
            {files.length > 5 && (
              <div className="text-xs text-muted text-center pt-1">还有 {files.length - 5} 个文件…</div>
            )}
          </div>

          <button
            onClick={handleConfirm}
            disabled={loading}
            className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50"
          >
            {loading ? (
              <span className="flex items-center justify-center gap-2">
                <Loader2 className="w-4 h-4 animate-spin" aria-hidden />
                重命名中…
              </span>
            ) : '确认重命名'}
          </button>
        </div>
      </div>
    </div>
  );
}
