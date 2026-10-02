import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Image as ImageIcon, Video, FileText, Music, Archive, Star, ChevronRight, FolderOpen } from 'lucide-react';
import { formatSize } from '../lib/utils';
import { useStorageStore } from '../store/storage';
import { useAuthStore } from '../store/auth';
import type { FileNode } from '../types';
import FileCard from '../components/home/FileCard';
import { getRecentFiles, clearRecentFiles, type RecentFile } from '../lib/recentFiles';
import { useFavoritesStore } from '../store/favorites';
import { personalFileSource } from '../lib/fileSource';
import PreviewModal from '../components/preview/PreviewModal';

const QUICK_CARDS = [
  { label: '图片', icon: ImageIcon, type: 'image', tile: 'bg-primary-100 text-primary-600' },
  { label: '视频', icon: Video, type: 'video', tile: 'bg-violet-500/15 text-violet-600' },
  { label: '文档', icon: FileText, type: 'document', tile: 'bg-primary-50 text-primary-600' },
  { label: '音乐', icon: Music, type: 'audio', tile: 'bg-orange-500/15 text-orange-600' },
  { label: '压缩包', icon: Archive, type: 'archive', tile: 'bg-violet-500/15 text-violet-600' },
];

/** Coerce a recent/favorite entry into a FileNode for preview. */
function toFileNode(f: RecentFile): FileNode {
  return {
    id: f.id, parentId: f.parentId ?? '', nodeType: 1, name: f.name, path: f.path ?? '',
    fileSize: f.fileSize, suffix: f.suffix, contentType: f.contentType, status: 0,
    thumbnailPath: null, createdAt: '', updatedAt: '',
  };
}

interface MenuState {
  x: number;
  y: number;
  file: FileNode;
}

export default function HomePage() {
  const navigate = useNavigate();
  const storage = useStorageStore((s) => s.storage);
  const user = useAuthStore((s) => s.user);
  const [accessedFiles, setAccessedFiles] = useState<RecentFile[]>([]);
  const favFiles = useFavoritesStore((s) => s.favorites);
  const fetchFavorites = useFavoritesStore((s) => s.fetchFavorites);
  const toggleFav = useFavoritesStore((s) => s.toggleFavorite);
  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);
  const [menu, setMenu] = useState<MenuState | null>(null);

  useEffect(() => {
    setAccessedFiles(getRecentFiles());
    fetchFavorites();
  }, [fetchFavorites]);

  const handleCardClick = (type: string | null) => {
    if (type) navigate(`/files/category/${type}`);
    else navigate('/files');
  };

  const openPreview = (file: FileNode, list: FileNode[]) => {
    const files = list.filter((f) => f.nodeType === 1);
    const idx = files.findIndex((f) => f.id === file.id);
    if (idx >= 0) setPreview({ files, index: idx });
  };

  const handlePreviewDownload = async (file: FileNode) => {
    const url = await personalFileSource.getDownloadUrl(file.id);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  const jumpToFolder = (file: FileNode) => {
    const pid = file.parentId && file.parentId !== '0' ? file.parentId : null;
    const target = pid ? `/files/${pid}?focusId=${file.id}` : `/files?focusId=${file.id}`;
    navigate(target);
  };

  const usedPercent = Math.round(storage?.percentage ?? 0);
  const available = Number(storage?.quota || 0) - Number(storage?.used || 0);
  const hour = new Date().getHours();
  const greeting = hour < 6 ? '凌晨好' : hour < 12 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';

  return (
    <div className="h-full overflow-auto" onClick={() => menu && setMenu(null)}>
      {/* 顶部问候：保持信息密度，避免营销式 Hero。 */}
      <div className="relative border-b border-border-light bg-surface">
        <div className="relative px-6 pt-6 pb-5">
          <div className="flex items-end justify-between gap-6">
            <div>
              <p className="text-muted text-sm mb-1">{greeting}，</p>
              <h1 className="text-xl font-semibold text-fg tracking-tight">{user?.nickname || user?.username || '用户'}</h1>
              <p className="text-muted text-sm mt-2">欢迎回到星云盘，你的文件随时可用。</p>
            </div>
            {storage && (
              <div className="hidden md:flex items-center gap-6 pb-1">
                <div className="text-center">
                  <div className="text-base font-semibold text-fg tabular-nums">{formatSize(storage.used)}</div>
                  <div className="text-xs text-muted mt-0.5">已用空间</div>
                </div>
                <div className="w-px h-10 bg-border" />
                <div className="text-center">
                  <div className="text-base font-semibold text-fg tabular-nums">{formatSize(available)}</div>
                  <div className="text-xs text-muted mt-0.5">可用空间</div>
                </div>
              </div>
            )}
          </div>
          {storage && (
            <div className="mt-4 h-1.5 bg-surface-2 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-[width] duration-500 ${usedPercent > 90 ? 'bg-danger' : 'bg-primary-600'}`}
                style={{ width: `${Math.min(usedPercent, 100)}%` }}
              />
            </div>
          )}
        </div>
      </div>

      <div className="px-6 py-6 space-y-7">
        {/* Quick access */}
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-semibold text-fg">快捷访问</h2>
            <button onClick={() => navigate('/files')} className="text-xs text-muted hover:text-primary-600 flex items-center gap-1 cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">
              全部文件 <ChevronRight className="w-3 h-3" aria-hidden />
            </button>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
            {QUICK_CARDS.map((card) => (
              <button
                key={card.label}
                onClick={() => handleCardClick(card.type)}
                aria-label={card.label} className="group relative flex items-center gap-3 min-h-[76px] px-4 py-3 bg-surface border border-border-light rounded-xl hover:bg-bg-hover hover:border-border transition-[background-color,border-color] duration-150 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg"
              >
                <div className={`w-9 h-9 ${card.tile} rounded-lg flex items-center justify-center flex-shrink-0`}>
                  <card.icon className="w-5 h-5" aria-hidden />
                </div>
                <span className="text-sm font-medium text-fg">{card.label}</span>
              </button>
            ))}
          </div>
        </section>

        {/* Favorites */}
        {favFiles.length > 0 && (
          <section>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Star className="w-4 h-4 text-amber-500 fill-amber-500" aria-hidden />
                <h2 className="text-base font-semibold text-fg">我的收藏</h2>
                <span className="text-xs text-muted tabular-nums">{favFiles.length}</span>
              </div>
              <button onClick={() => navigate('/favorites')} className="text-xs text-muted hover:text-primary-600 flex items-center gap-1 cursor-pointer transition-colors">
                查看全部 <ChevronRight className="w-3 h-3" aria-hidden />
              </button>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              {favFiles.slice(0, 8).map((file) => {
                return (
                  <FileCard
                    key={file.id}
                    file={file}
                    actionLabel="收藏"
                    subtitle={file.path || '/'}
                    onOpen={() => {
                      if (file.nodeType === 0) jumpToFolder(file);
                      else openPreview(file, favFiles);
                    }}
                    onContextMenu={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, file }); }}
                    trailing={
                      <button
                        onClick={async (e) => { e.stopPropagation(); const added = await toggleFav(file); if (!added) fetchFavorites(); }}
                        className="text-muted hover:text-amber-500 transition-colors opacity-0 group-hover:opacity-100 cursor-pointer"
                        aria-label="取消收藏"
                      >
                        <Star className="w-4 h-4 fill-current" aria-hidden />
                      </button>
                    }
                  />
                );
              })}
            </div>
          </section>
        )}

        {/* Recently accessed */}
        {accessedFiles.length > 0 && (
          <section>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-fg">最近访问</h2>
              <button onClick={() => { clearRecentFiles(); setAccessedFiles([]); }} className="text-xs text-muted hover:text-fg cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">清除记录</button>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              {accessedFiles.slice(0, 8).map((file) => {
                const fn = toFileNode(file);
                return (
                  <FileCard
                    key={file.id}
                    file={fn}
                    actionLabel="访问"
                    subtitle={file.fileSize != null && Number(file.fileSize) > 0 ? <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span> : undefined}
                    onOpen={() => openPreview(fn, accessedFiles.map(toFileNode))}
                    onContextMenu={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, file: fn }); }}
                  />
                );
              })}
            </div>
          </section>
        )}

      </div>

      {preview && (
        <PreviewModal
          files={preview.files}
          currentIndex={preview.index}
          onClose={() => setPreview(null)}
          onDownload={handlePreviewDownload}
        />
      )}

      {menu && (
        <div
          className="fixed z-[100] w-44 bg-surface rounded-lg shadow-md border border-border py-1.5 animate-scale-in"
          style={{ left: menu.x, top: menu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            onClick={() => { openPreview(menu.file, [menu.file]); setMenu(null); }}
            className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 cursor-pointer transition-colors"
          >
            <FolderOpen className="w-4 h-4" aria-hidden />
            <span>预览</span>
          </button>
          <button
            onClick={() => { jumpToFolder(menu.file); setMenu(null); }}
            className="w-full flex items-center gap-2.5 px-3 py-1.5 text-sm text-fg hover:bg-surface-2 cursor-pointer transition-colors"
          >
            <FolderOpen className="w-4 h-4" aria-hidden />
            <span>跳转到所在目录</span>
          </button>
        </div>
      )}
    </div>
  );
}
