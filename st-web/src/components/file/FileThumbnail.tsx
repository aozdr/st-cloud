import { useState, useEffect } from 'react';
import api from '../../lib/api';
import { getFileTypeConfig, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileTypeIcon from './FileTypeIcon';

const IMAGE_SUFFIXES = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp', 'ico'];

interface Props {
  file: FileNode;
  size?: 'sm' | 'lg' | 'xl' | 'xxl';
}

export default function FileThumbnail({ file, size = 'sm' }: Props) {
  const [url, setUrl] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const isImage = file.nodeType === 1 && IMAGE_SUFFIXES.includes((file.suffix || '').toLowerCase());

  useEffect(() => {
    if (!isImage) return;
    let cancelled = false;
    setLoaded(false);
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

  const sizeClass = size === 'xxl' ? 'w-28 h-28' : size === 'xl' ? 'w-20 h-20' : size === 'lg' ? 'w-10 h-10' : 'w-8 h-8';
  // Small list/table thumbnails: contained with padding so images don't look like
  // jarring solid squares next to transparent file-type icons.
  const pad = size === 'sm' || size === 'lg';

  if (isImage) {
    return (
      <div className={`${sizeClass} rounded-lg overflow-hidden flex-shrink-0 relative`}>
        <div className={cn('absolute inset-0 flex items-center justify-center transition-opacity duration-300', loaded ? 'opacity-0' : 'opacity-100', pad && 'p-0.5')}>
          <FileTypeIcon config={config} size={size} isFolder={false} suffix={file.suffix} />
        </div>
        {url && (
          <img
            src={url}
            alt={file.name}
            className={cn('absolute inset-0 w-full h-full transition-opacity duration-300', loaded ? 'opacity-100' : 'opacity-0', pad ? 'object-contain p-0.5' : 'object-cover')}
            loading="lazy"
            draggable={false}
            onLoad={() => setLoaded(true)}
          />
        )}
      </div>
    );
  }

  return <FileTypeIcon config={config} size={size} isFolder={file.nodeType === 0} suffix={file.suffix} />;
}