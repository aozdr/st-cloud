import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Image as ImageIcon, Video, FileText, Music, Archive, FolderClosed, Clock, Star, ChevronRight, FolderOpen } from 'lucide-react';
import api from '../lib/api';
import { formatSize, formatDate } from '../lib/utils';
import { useStorageStore } from '../store/storage';
import { useAuthStore } from '../store/auth';
import type { FileNode, PageResult } from '../types';
import FileThumbnail from '../components/file/FileThumbnail';
import FileCard from '../components/home/FileCard';
import { getRecentFiles, clearRecentFiles, type RecentFile } from '../lib/recentFiles';
import { useFavoritesStore } from '../store/favorites';
import { personalFileSource } from '../lib/fileSource';
import PreviewModal from '../components/preview/PreviewModal';

const QUICK_CARDS = [
  { label: '图片', icon: ImageIcon, type: 'image', gradient: 'from-blue-500 to-blue-600', glow: 'shadow-[0_4px_20px_-4px_rgba(59,130,246,0.4)]' },
  { label: '视频', icon: Video, type: 'video', gradient: 'from-purple-500 to-purple-600', glow: 'shadow-[0_4px_20px_-4px_rgba(168,85,247,0.4)]' },
  { label: '文档', icon: FileText, type: 'document', gradient: 'from-amber-500 to-orange-600', glow: 'shadow-[0_4px_20px_-4px_rgba(245,158,11,0.4)]' },
  { label: '音乐', icon: Music, type: 'audio', gradient: 'from-pink-500 to-rose-600', glow: 'shadow-[0_4px_20px_-4px_rgba(236,72,153,0.4)]' },
  { label: '压缩包', icon: Archive, type: 'archive', gradient: 'from-emerald-500 to-green-600', glow: 'shadow-[0_4px_20px_-4px_rgba(16,185,129,0.4)]' },
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
  const { user } = useAuthStore();
  const [recentFiles, setRecentFiles] = useState<FileNode[]>([]);
  const [accessedFiles, setAccessedFiles] = useState<RecentFile[]>([]);
  const { favorites: favFiles, fetchFavorites, toggleFavorite: toggleFav } = useFavoritesStore();
  const [loading, setLoading] = useState(true);
  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);
  const [menu, setMenu] = useState<MenuState | null>(null);

  const fetchRecent = useCallback(async () => {
    try {
      const data: PageResult<FileNode> = await api.get('/file/list', { params: { parentId: '0', page: 1, size: 50 } });
      const files = (data.records || [])
        .filter((f) => f.nodeType === 1)
        .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
        .slice(0, 8);
      setRecentFiles(files);
    } catch { setRecentFiles([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    fetchRecent();
    setAccessedFiles(getRecentFiles());
    fetchFavorites();
  }, [fetchRecent, fetchFavorites]);

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
      {/* Hero banner */}
      <div className="relative overflow-hidden brand-gradient">
        <div className="absolute top-0 right-0 w-96 h-96 bg-primary-600/15 rounded-full blur-3xl" aria-hidden />
        <div className="absolute bottom-0 left-1/3 w-64 h-64 bg-primary-500/10 rounded-full blur-3xl" aria-hidden />
        <div className="relative max-w-6xl mx-auto px-6 pt-10 pb-8">
          <div className="flex items-end justify-between gap-6">
            <div>
              <p className="text-muted text-sm mb-1">{greeting}，</p>
              <h1 className="text-3xl font-bold text-fg tracking-tight">{user?.nickname || user?.username || '用户'}</h1>
              <p className="text-muted text-sm mt-2">欢迎回到星云盘，你的文件随时可用。</p>
            </div>
            {storage && (
              <div className="hidden md:flex items-center gap-6 pb-1">
                <div className="text-center">
                  <div className="text-2xl font-bold text-fg tabular-nums">{formatSize(storage.used)}</div>
                  <div className="text-xs text-muted mt-0.5">已用空间</div>
                </div>
                <div className="w-px h-10 bg-border" />
                <div className="text-center">
                  <div className="text-2xl font-bold text-fg tabular-nums">{formatSize(available)}</div>
                  <div className="text-xs text-muted mt-0.5">可用空间</div>
                </div>
              </div>
            )}
          </div>
          {storage && (
            <div className="mt-5 h-1 bg-surface-2 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-[width] duration-700 ${usedPercent > 90 ? 'bg-gradient-to-r from-red-500 to-orange-400' : 'bg-gradient-to-r from-primary-600 via-primary-500 to-primary-400'}`}
                style={{ width: `${Math.min(usedPercent, 100)}%` }}
              />
            </div>
          )}
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-6 py-6 space-y-8">
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
                aria-label={card.label} className={`group relative flex flex-col items-center gap-3 p-5 bg-gradient-to-br ${card.gradient} rounded-2xl hover:scale-[1.03] transition-[transform,box-shadow] duration-300 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg ${card.glow}`}
              >
                <div className="w-12 h-12 bg-white/20 rounded-xl flex items-center justify-center backdrop-blur-sm">
                  <card.icon className="w-6 h-6 text-white" aria-hidden />
                </div>
                <span className="text-sm font-medium text-white">{card.label}</span>
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

        {/* Recent files */}
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-semibold text-fg">最近文件</h2>
            <button onClick={() => navigate('/files')} className="text-xs text-muted hover:text-primary-600 flex items-center gap-1 cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">
              查看全部 <ChevronRight className="w-3 h-3" aria-hidden />
            </button>
          </div>
          {loading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              {Array.from({ length: 8 }).map((_, i) => (
                <div key={i} className="flex items-center gap-3 p-3 bg-surface rounded-xl">
                  <div className="w-8 h-8 bg-surface-2 rounded-lg animate-pulse flex-shrink-0 opacity-60" />
                  <div className="flex-1 space-y-1.5">
                    <div className="h-3.5 bg-surface-2 rounded animate-pulse w-2/3 opacity-60" />
                    <div className="h-3 bg-surface-2 rounded animate-pulse w-1/3 opacity-60" />
                  </div>
                </div>
              ))}
            </div>
          ) : recentFiles.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-muted">
              <FolderClosed className="w-12 h-12 mb-3 opacity-20" aria-hidden />
              <p className="text-sm">暂无文件，点击上方"上传文件"开始吧</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              {recentFiles.map((file) => (
                <FileCard
                  key={file.id}
                  file={file}
                  actionLabel="文件"
                  icon={<FileThumbnail file={file} size="sm" />}
                  onOpen={() => openPreview(file, recentFiles)}
                  onContextMenu={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, file }); }}
                  subtitle={
                    <span className="flex items-center gap-2">
                      <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                      <span className="text-muted">·</span>
                      <span className="flex items-center gap-0.5 tabular-nums truncate">
                        <Clock className="w-3 h-3 flex-shrink-0" aria-hidden />
                        {formatDate(file.updatedAt)}
                      </span>
                    </span>
                  }
                />
              ))}
            </div>
          )}
        </section>
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
