import { useState, useEffect } from 'react';
import api from '../../lib/api';
import { getFileTypeConfig, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileTypeIcon from './FileTypeIcon';
import { Check, MoreHorizontal } from 'lucide-react';

interface Props {
  files: FileNode[];
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
  onItemDragStart?: (e: React.DragEvent, node: FileNode) => void;
  onFolderDragOver?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDragLeave?: (e: React.DragEvent, folder: FileNode) => void;
  onFolderDrop?: (e: React.DragEvent, folder: FileNode) => void;
  dragOverFolderId?: string | null;
  onItemMenu?: (e: React.MouseEvent, node: FileNode) => void;
}

const IMAGE_SUFFIXES = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp', 'ico'];

function isImage(file: FileNode): boolean {
  if (file.nodeType === 0) return false;
  const suffix = (file.suffix || '').toLowerCase();
  return IMAGE_SUFFIXES.includes(suffix);
}

function GridIcon({ file }: { file: FileNode }) {
  const [url, setUrl] = useState<string | null>(null);
  const config = getFileTypeConfig(file.nodeType, file.suffix);

  useEffect(() => {
    if (!isImage(file)) return;
    let cancelled = false;
    api
      .get<string>(`/preview/${file.id}/thumbnail`, { params: { size: 'sm' } })
      .then((u) => {
        if (!cancelled) setUrl(u);
      })
      .catch(() => {
        if (!cancelled) {
          const token = localStorage.getItem('accessToken');
          setUrl(`/api/file/${file.id}/stream?token=${encodeURIComponent(token || '')}&inline=1`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [file.id]);

  if (isImage(file) && url) {
    return <img src={url} alt={file.name} className="w-full h-full object-cover" loading="lazy" draggable={false} />;
  }

  return <FileTypeIcon config={config} size="xl" isFolder={file.nodeType === 0} suffix={file.suffix} />;
}

export default function FileGrid({ files, selectedIds, focusedId, cutIds, onSelect, onContextMenu, onDoubleClick, onItemDragStart, onFolderDragOver, onFolderDragLeave, onFolderDrop, dragOverFolderId, onItemMenu }: Props) {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-8 gap-3">
      {files.map((file, index) => {
        const isSelected = selectedIds.has(file.id);

        return (
          <div
            key={file.id}
            data-file-id={file.id}
            draggable
            onDragStart={(e) => onItemDragStart?.(e, file)}
            onDragOver={file.nodeType === 0 ? (e) => onFolderDragOver?.(e, file) : undefined}
            onDragLeave={file.nodeType === 0 ? (e) => onFolderDragLeave?.(e, file) : undefined}
            onDrop={file.nodeType === 0 ? (e) => onFolderDrop?.(e, file) : undefined}
            onClick={(e) => onSelect(file.id, e)}
            onDoubleClick={() => onDoubleClick(file)}
            onContextMenu={(e) => onContextMenu(e, file)}
            style={{ animationDelay: `${Math.min(index, 24) * 24}ms` }}
            className={cn(
              'group relative flex flex-col rounded-xl p-2.5 cursor-pointer select-none transition-all duration-200 animate-file-enter',
              isSelected
                ? 'bg-primary-50/70'
                : dragOverFolderId === file.id
                  ? 'bg-primary-50 ring-2 ring-primary-400'
                  : focusedId === file.id
                    ? 'bg-stone-100/80'
                    : 'hover:bg-stone-50',
              cutIds?.has(file.id) && 'opacity-50'
            )}
          >
            <button
              onClick={(e) => { e.stopPropagation(); onSelect(file.id, e); }}
              aria-label="选择"
              className={cn(
                'absolute top-1.5 left-1.5 z-10 w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center transition-all',
                isSelected ? 'bg-primary-600 border-primary-600 opacity-100' : 'border-stone-300 bg-white/90 opacity-0 group-hover:opacity-100'
              )}
            >
              {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} aria-hidden />}
            </button>

            {onItemMenu && (
              <button
                onClick={(e) => { e.stopPropagation(); onItemMenu(e, file); }}
                onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); onContextMenu(e, file); }}
                aria-label="更多操作"
                className="absolute top-1.5 right-1.5 z-10 w-7 h-7 rounded-lg flex items-center justify-center text-stone-500 bg-white/80 hover:bg-white hover:text-stone-900 opacity-0 group-hover:opacity-100 transition-all"
              >
                <MoreHorizontal className="w-4 h-4" aria-hidden />
              </button>
            )}

            <div className="flex items-center justify-center h-28 mb-1.5 rounded-lg overflow-hidden">
              <GridIcon file={file} />
            </div>
            <div
              className={cn(
                'text-xs leading-tight text-center max-w-full truncate px-1',
                isSelected ? 'text-primary-700 font-medium' : 'text-stone-600'
              )}
              title={file.name}
            >
              {file.name}
            </div>
          </div>
        );
      })}
    </div>
  );
}