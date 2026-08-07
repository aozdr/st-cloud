import { useState, useEffect } from 'react';
import api from '../../lib/api';
import { getFileTypeConfig } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileTypeIcon from './FileTypeIcon';

const IMAGE_SUFFIXES = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp', 'ico'];

interface Props {
  file: FileNode;
  size?: 'sm' | 'lg' | 'xl';
}

export default function FileThumbnail({ file, size = 'sm' }: Props) {
  const [url, setUrl] = useState<string | null>(null);
  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const isImage = file.nodeType === 1 && IMAGE_SUFFIXES.includes((file.suffix || '').toLowerCase());

  useEffect(() => {
    if (!isImage) return;
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
  }, [file.id, isImage]);

  if (isImage && url) {
    const sizeClass = size === 'xl' ? 'w-20 h-20' : size === 'lg' ? 'w-10 h-10' : 'w-8 h-8';
    return (
      <div className={`${sizeClass} rounded-lg overflow-hidden flex-shrink-0`}>
        <img src={url} alt={file.name} className="w-full h-full object-cover" loading="lazy" draggable={false} />
      </div>
    );
  }

  return <FileTypeIcon config={config} size={size} isFolder={file.nodeType === 0} suffix={file.suffix} />;
}