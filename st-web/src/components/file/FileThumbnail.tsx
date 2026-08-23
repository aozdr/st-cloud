import { memo, useState, useEffect } from 'react';
import api, { buildStreamUrl } from '../../lib/api';
import { getFileTypeConfig, isImage, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import FileTypeIcon from './FileTypeIcon';

interface Props {
  file: FileNode;
  size?: 'sm' | 'lg' | 'xl' | 'xxl' | 'xxxl';
  /** 网格大图模式：显示模糊背景层；配合 className 撑满容器 */
  blur?: boolean;
  className?: string;
}

function FileThumbnail({ file, size = 'sm', blur = false, className }: Props) {
  const [url, setUrl] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const img = file.nodeType === 1 && isImage(file.suffix);

  useEffect(() => {
    if (!img) return;
    let cancelled = false;
    setLoaded(false);
    api
      .get<string>(`/preview/${file.id}/thumbnail`, { params: { size: 'sm' } })
      .then((u) => {
        if (!cancelled) setUrl(u);
      })
      .catch(() => {
        // 缩略图接口失败：改用 download-token 兜底（download 令牌后端允许 URL query，绝不暴露 access token）
        if (cancelled) return;
        api
          .post<{ token: string }>(`/file/${file.id}/download-token`)
          .then((d) => {
            if (!cancelled) setUrl(buildStreamUrl(file.id, { token: d.token, inline: true }));
          })
          .catch(() => {
            if (!cancelled) setUrl(null);
          });
      });
    return () => {
      cancelled = true;
    };
  }, [file.id, img]);

  if (!img) {
    return <FileTypeIcon config={config} size={size} isFolder={file.nodeType === 0} suffix={file.suffix} />;
  }

  const sizeClass = size === 'xxxl' ? 'w-36 h-36' : size === 'xxl' ? 'w-28 h-28' : size === 'xl' ? 'w-20 h-20' : size === 'lg' ? 'w-10 h-10' : 'w-8 h-8';
  // 列表小尺寸：contain + padding，避免图片贴边；网格大图（blur）contain 完整显示，由模糊背景层填充留白
  const pad = size === 'sm' || size === 'lg';

  return (
    <div className={cn(sizeClass, 'rounded-lg overflow-hidden flex-shrink-0 relative', className)}>
      {/* 模糊背景层：网格大图用同图 blur 填充，消除 letterbox 留白 */}
      {blur && url && (
        <img
          src={url}
          alt=""
          aria-hidden
          className="absolute inset-0 w-full h-full object-cover blur-xl scale-110 opacity-60"
        />
      )}
      {/* 占位图标 */}
      <div className={cn('absolute inset-0 flex items-center justify-center transition-opacity duration-300', loaded ? 'opacity-0' : 'opacity-100', pad && 'p-0.5')}>
        <FileTypeIcon config={config} size={size} isFolder={false} suffix={file.suffix} />
      </div>
      {/* 前景图：居中完整展示原始比例（模糊背景层负责填充留白，避免竖图拉伸发糊） */}
      {url && (
        <img
          src={url}
          alt={file.name}
          className={cn('absolute inset-0 w-full h-full transition-opacity duration-300', loaded ? 'opacity-100' : 'opacity-0', pad ? 'object-contain p-0.5' : blur ? 'object-contain' : 'object-cover')}
          loading="lazy"
          draggable={false}
          onLoad={() => setLoaded(true)}
        />
      )}
    </div>
  );
}

export default memo(FileThumbnail);
