import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { X, FolderOpen, Download, CheckCircle2, ListChecks } from 'lucide-react';

interface Props {
  fileName: string;
  fileSize: number;
  onConfirm: (savePath: string) => Promise<boolean>;
  onClose: () => void;
}

function formatSize(bytes: number): string {
  if (!bytes) return '-';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let size = bytes;
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024;
    i++;
  }
  return `${size.toFixed(1)} ${units[i]}`;
}

export default function DownloadDialog({ fileName, fileSize, onConfirm, onClose }: Props) {
  const navigate = useNavigate();
  const [savePath, setSavePath] = useState('');
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    window.electronAPI?.getDownloadsPath().then((dir) => {
      setSavePath(`${dir}\\${fileName}`);
    }).catch(() => {
      setSavePath(fileName);
    });
  }, [fileName]);

  const handleBrowse = async () => {
    const dirs = await window.electronAPI?.selectFolder();
    if (dirs && dirs.length > 0) {
      setSavePath(`${dirs[0]}\\${fileName}`);
    }
  };

  const handleConfirm = async () => {
    if (!savePath.trim()) return;
    setLoading(true);
    const ok = await onConfirm(savePath.trim());
    setLoading(false);
    if (ok) setDone(true);
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-stone-900/40 animate-fade-in" onClick={onClose}>
      <div
        className="bg-white rounded-xl shadow-lg w-[460px] animate-dialog-pop"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-stone-100">
          <h3 className="text-base font-semibold text-stone-900">下载文件</h3>
          <button onClick={onClose} className="text-stone-400 hover:text-stone-900 cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>

        {done ? (
          /* Success state */
          <div className="px-5 py-8 flex flex-col items-center">
            <div className="w-12 h-12 rounded-full bg-green-50 flex items-center justify-center mb-3">
              <CheckCircle2 className="w-6 h-6 text-green-500" />
            </div>
            <p className="text-sm text-stone-700 mb-4">已添加到下载队列</p>
            <div className="flex gap-2">
              <button onClick={onClose} className="btn-secondary">关闭</button>
              <button
                onClick={() => { onClose(); navigate('/transfers'); }}
                className="btn-primary flex items-center gap-1.5"
              >
                <ListChecks className="w-4 h-4" />
                查看传输列表
              </button>
            </div>
          </div>
        ) : (
          <>
            {/* Body */}
            <div className="px-5 py-4 space-y-4">
              {/* File info */}
              <div className="flex items-center gap-3 p-3 bg-stone-50 rounded-lg">
                <div className="w-10 h-10 rounded-lg bg-primary-50 flex items-center justify-center flex-shrink-0">
                  <Download className="w-5 h-5 text-primary-600" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-stone-900 truncate">{fileName}</p>
                  <p className="text-xs text-stone-400">{formatSize(fileSize)}</p>
                </div>
              </div>

              {/* Save path input */}
              <div>
                <label className="block text-xs font-medium text-stone-500 mb-1.5">保存至</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={savePath}
                    onChange={(e) => setSavePath(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleConfirm()}
                    placeholder="选择保存位置"
                    className="flex-1 px-3 py-2 text-sm border border-stone-200 rounded-lg focus:outline-none focus:border-primary-400 focus:ring-1 focus:ring-primary-400 bg-white"
                    autoFocus
                  />
                  <button
                    onClick={handleBrowse}
                    className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-stone-600 bg-stone-100 hover:bg-stone-200 rounded-lg cursor-pointer transition-colors duration-150 flex-shrink-0"
                  >
                    <FolderOpen className="w-4 h-4" />
                    浏览
                  </button>
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="flex justify-end gap-2 px-5 py-3 border-t border-stone-100">
              <button onClick={onClose} className="btn-secondary">取消</button>
              <button
                onClick={handleConfirm}
                disabled={loading || !savePath.trim()}
                className="btn-primary"
              >
                {loading ? '处理中...' : '下载'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
