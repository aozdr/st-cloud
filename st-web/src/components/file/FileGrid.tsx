import { useState, useEffect } from 'react';
import api from '../../lib/api';
import { getFileTypeConfig, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileTypeIcon from './FileTypeIcon';

interface Props {
  files: FileNode[];
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
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
    return (
      <div className="h-20 w-20 rounded-lg overflow-hidden flex-shrink-0">
        <img src={url} alt={file.name} className="w-full h-full object-cover" loading="lazy" draggable={false} />
      </div>
    );
  }

  return <FileTypeIcon config={config} size="xl" isFolder={file.nodeType === 0} suffix={file.suffix} />;
}

export default function FileGrid({ files, selectedIds, focusedId, cutIds, onSelect, onContextMenu, onDoubleClick }: Props) {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-8 gap-3">
      {files.map((file) => {
        const isSelected = selectedIds.has(file.id);

        return (
          <div
            key={file.id}
            data-file-id={file.id}
            onClick={(e) => onSelect(file.id, e)}
            onDoubleClick={() => onDoubleClick(file)}
            onContextMenu={(e) => onContextMenu(e, file)}
            className={cn(
              'group relative flex flex-col rounded-lg p-3 cursor-pointer select-none transition-colors duration-100',
              isSelected
                ? 'bg-primary-50'
                : focusedId === file.id
                  ? 'bg-stone-100'
                  : 'hover:bg-stone-100',
              cutIds?.has(file.id) && 'opacity-50'
            )}
          >
            <div className="flex items-center justify-center h-20 mb-2">
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
