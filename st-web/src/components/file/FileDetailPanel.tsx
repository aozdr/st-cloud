import type { FileNode } from '../../types';
import { useEffect, useState } from 'react';
import api from '../../lib/api';
import type { FolderSizeInfo } from '../../hooks/useFolderSizes';
import { getFileTypeConfig, formatSize, formatDate, cn } from '../../lib/utils';
import FileThumbnail from './FileThumbnail';
import { X, FolderOpen, Clock, HardDrive, Calendar, Hash, Bell, BellRing } from 'lucide-react';
import { useFileWatch } from '../../hooks/useFileWatch';

interface Props {
  file: FileNode;
  onClose: () => void;
  /** sidebar=右侧边栏（默认，兼容 FileBrowser 其它页面）；panel=页面级详情视图内容区（占满容器） */
  variant?: 'sidebar' | 'panel';
}

export default function FileDetailPanel({ file, onClose, variant = 'sidebar' }: Props) {
  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const isPanel = variant === 'panel';
  const [folderSize, setFolderSize] = useState<FolderSizeInfo | null>(null);
  const { watching, loading: watchLoading, saving: watchSaving, error: watchError, toggle: toggleWatch } = useFileWatch(file.id);

  useEffect(() => {
    setFolderSize(null);
    if (file.nodeType !== 0) return;
    let cancelled = false;
    api.get<FolderSizeInfo>(`/file/${file.id}/folder-size`)
      .then((res) => { if (!cancelled) setFolderSize(res); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [file.id, file.nodeType]);

  return (
    <aside
      className={cn(
        'h-full bg-surface overflow-y-auto flex flex-col',
        isPanel ? 'w-full' : 'w-80 flex-shrink-0 border-l border-border animate-slide-up',
      )}
    >
      {!isPanel && (
        <div className="flex items-center justify-between px-4 py-3 border-b border-border">
          <span className="text-sm font-semibold text-fg">详情</span>
          <button
            onClick={onClose}
            aria-label="关闭详情面板"
            className="text-muted hover:text-fg cursor-pointer p-0.5 rounded hover:bg-surface-2 transition-colors"
          >
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
      )}

      {/* 面板模式：内容全宽/全高展示，限宽居中保证长元数据可读 */}
      <div className={cn('w-full', isPanel && 'mx-auto max-w-2xl')}>
        <div className={cn('flex flex-col items-center border-b border-border', isPanel ? 'px-4 py-8' : 'px-4 py-6')}>
          <div className={cn('rounded-2xl bg-surface-2 flex items-center justify-center mb-5 overflow-hidden', isPanel ? 'w-32 h-32' : 'w-28 h-28')}>
            <FileThumbnail file={file} size="xxl" />
          </div>
          <span className={cn('text-fg text-center leading-snug break-words line-clamp-2 max-w-full mt-1', isPanel ? 'text-base font-semibold' : 'text-sm font-medium')}>{file.name}</span>
          <span className="mt-1.5 text-xs text-muted">{file.nodeType === 0 ? '文件夹' : config.label}</span>
          <button
            type="button"
            onClick={() => void toggleWatch()}
            disabled={watchLoading || watchSaving}
            aria-pressed={watching}
            className={cn(
              'mt-4 inline-flex items-center justify-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60',
              watching
                ? 'border-primary-200 bg-primary-500/10 text-primary-600 hover:bg-primary-500/15'
                : 'border-border bg-surface text-muted hover:border-primary-300 hover:bg-primary-500/5 hover:text-primary-600',
            )}
          >
            {watching ? <BellRing className="h-3.5 w-3.5" aria-hidden /> : <Bell className="h-3.5 w-3.5" aria-hidden />}
            <span>{watchLoading ? '读取中…' : watchSaving ? '保存中…' : watching ? '已关注' : '关注变更'}</span>
          </button>
          <p className="mt-2 max-w-[18rem] text-center text-[11px] leading-relaxed text-muted">
            {file.nodeType === 0 ? '提醒此文件夹及子文件夹内的变更' : '他人修改时通知我'}
          </p>
          {watchError && <p className="mt-1 text-center text-[11px] text-danger">{watchError}</p>}
        </div>

        <div className={cn('space-y-3', isPanel ? 'px-4 py-6' : 'px-4 py-4')}>
          {file.nodeType === 1 && (
            <DetailRow icon={HardDrive} label="大小" value={formatSize(file.fileSize)} />
          )}
          {file.nodeType === 0 && (
            <DetailRow
              icon={HardDrive}
              label="总大小"
              value={folderSize ? formatSize(folderSize.size) : '计算中…'}
            />
          )}
          {file.nodeType === 0 && folderSize && (
            <DetailRow
              icon={Hash}
              label="内容"
              value={`${folderSize.fileCount} 个文件 · ${folderSize.folderCount} 个文件夹`}
            />
          )}
          {file.path && file.path !== '/' && (
            <DetailRow icon={FolderOpen} label="位置" value={file.path} />
          )}
          {file.suffix && (
            <DetailRow icon={Hash} label="扩展名" value={`.${file.suffix}`} />
          )}
          <DetailRow icon={Calendar} label="创建时间" value={formatDate(file.createdAt)} />
          <DetailRow icon={Clock} label="修改时间" value={formatDate(file.updatedAt)} />
        </div>
      </div>
    </aside>
  );
}

function DetailRow({ icon: Icon, label, value }: { icon: typeof Clock; label: string; value: string }) {
  return (
    <div className="flex items-start gap-3 rounded-lg px-1 py-1 hover:bg-surface-2/60 transition-colors">
      <Icon className="w-4 h-4 text-muted flex-shrink-0 mt-0.5" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="text-xs text-muted mb-0.5">{label}</div>
        <div className="text-sm text-fg break-all">{value || '-'}</div>
      </div>
    </div>
  );
}
