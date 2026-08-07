import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Image as ImageIcon, Video, FileText, Music, Archive, FolderClosed, Clock, HardDrive } from 'lucide-react';
import api from '../lib/api';
import { formatSize, formatDate } from '../lib/utils';
import { useStorageStore } from '../store/storage';
import type { FileNode, PageResult } from '../types';
import FileThumbnail from '../components/file/FileThumbnail';
import { getRecentFiles, clearRecentFiles, type RecentFile } from '../lib/recentFiles';
import { getFavorites, removeFavorite, type FavoriteFile } from '../lib/favorites';
import { Star } from 'lucide-react';
import { getFileTypeConfig } from '../lib/utils';
import FileTypeIcon from '../components/file/FileTypeIcon';

const QUICK_CARDS = [
  { label: '图片', icon: ImageIcon, fileType: 'image', color: 'bg-blue-50 text-blue-600' },
  { label: '视频', icon: Video, fileType: 'video', color: 'bg-purple-50 text-purple-600' },
  { label: '文档', icon: FileText, fileType: 'document', color: 'bg-orange-50 text-orange-600' },
  { label: '音乐', icon: Music, fileType: 'audio', color: 'bg-pink-50 text-pink-600' },
  { label: '压缩包', icon: Archive, fileType: 'archive', color: 'bg-green-50 text-green-600' },
  { label: '全部文件', icon: FolderClosed, fileType: null, color: 'bg-stone-100 text-stone-600' },
];

export default function HomePage() {
  const navigate = useNavigate();
  const storage = useStorageStore((s) => s.storage);
  const [recentFiles, setRecentFiles] = useState<FileNode[]>([]);
  const [accessedFiles, setAccessedFiles] = useState<RecentFile[]>([]);
  const [favFiles, setFavFiles] = useState<FavoriteFile[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchRecent = useCallback(async () => {
    try {
      const data: PageResult<FileNode> = await api.get('/file/list', { params: { parentId: '0', page: 1, size: 50 } });
      const files = (data.records || [])
        .filter((f) => f.nodeType === 1)
        .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
        .slice(0, 6);
      setRecentFiles(files);
    } catch {
      setRecentFiles([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRecent();
    setAccessedFiles(getRecentFiles());
    setFavFiles(getFavorites());
  }, [fetchRecent]);

  const handleCardClick = (fileType: string | null) => {
    if (fileType) {
      navigate(`/search?keyword=*&fileType=${fileType}&_t=${Date.now()}`);
    } else {
      navigate('/files');
    }
  };

  const usedPercent = storage?.percentage ?? 0;

  return (
    <div className="h-full overflow-auto bg-stone-50">
      <div className="max-w-5xl mx-auto px-6 py-8">
        {storage && (
          <div className="flex items-center gap-3 mb-6 px-5 py-3.5 bg-white rounded-xl border border-stone-200">
            <HardDrive className="w-5 h-5 text-stone-400 flex-shrink-0" aria-hidden />
            <div className="flex-1 min-w-0">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-sm font-medium text-stone-700">存储空间</span>
                <span className="text-xs text-stone-500 tabular-nums">{formatSize(storage.used)} / {formatSize(storage.quota)}</span>
              </div>
              <div className="w-full h-1.5 bg-stone-100 rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full transition-all duration-300 ${usedPercent > 90 ? 'bg-red-500' : 'bg-primary-600'}`}
                  style={{ width: `${Math.min(usedPercent, 100)}%` }}
                />
              </div>
            </div>
          </div>
        )}

                {favFiles.length > 0 && (
          <>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-1.5">
                <Star className="w-3.5 h-3.5 text-amber-500 fill-amber-500" aria-hidden />
                <h2 className="text-sm font-semibold text-stone-500">我的收藏</h2>
              </div>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-8">
              {favFiles.map((file) => {
                const config = getFileTypeConfig(file.nodeType, file.suffix);
                return (
                  <div
                    key={file.id}
                    onClick={() => navigate(file.parentId && file.parentId !== '0' ? `/files/${file.parentId}` : '/files')}
                    className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer group"
                  >
                    <div className="flex-shrink-0">
                      <FileTypeIcon config={config} size="sm" isFolder={file.nodeType === 0} suffix={file.suffix} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-stone-800 truncate">{file.name}</div>
                      <div className="flex items-center gap-2 text-xs text-stone-400 mt-0.5">
                        {file.path && <span className="truncate max-w-[200px]" title={file.path}>{file.path}</span>}
                        {file.fileSize != null && Number(file.fileSize) > 0 && (
                          <>
                            <span className="text-stone-300">·</span>
                            <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                          </>
                        )}
                      </div>
                    </div>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeFavorite(file.id); setFavFiles(getFavorites()); }}
                      className="text-stone-300 hover:text-amber-500 transition-colors opacity-0 group-hover:opacity-100 cursor-pointer"
                      aria-label="取消收藏"
                    >
                      <Star className="w-4 h-4 fill-current" />
                    </button>
                  </div>
                );
              })}
            </div>
          </>
        )}

        {accessedFiles.length > 0 && (
          <>
            <div className="flex items-center justify-between mb-3"><h2 className="text-sm font-semibold text-stone-500">最近访问</h2><button onClick={() => { clearRecentFiles(); setAccessedFiles([]); }} className="text-xs text-stone-400 hover:text-stone-600 cursor-pointer transition-colors">清除记录</button></div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-8">
              {accessedFiles.map((file) => {
                const config = getFileTypeConfig(1, file.suffix);
                return (
                  <div
                    key={file.id}
                    onClick={() => navigate('/files')}
                    className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer"
                  >
                    <div className="flex-shrink-0">
                      <FileTypeIcon config={config} size="sm" isFolder={false} suffix={file.suffix} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-stone-800 truncate">{file.name}</div>
                      <div className="flex items-center gap-2 text-xs text-stone-400 mt-0.5">
                        {file.path && <span className="truncate max-w-[200px]" title={file.path}>{file.path}</span>}
                        {file.fileSize != null && Number(file.fileSize) > 0 && (
                          <>
                            <span className="text-stone-300">·</span>
                            <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        )}

        <h2 className="text-sm font-semibold text-stone-500 mb-3">快捷访问</h2>
        <div className="grid grid-cols-3 md:grid-cols-6 gap-3 mb-8">
          {QUICK_CARDS.map((card) => (
            <button
              key={card.label}
              onClick={() => handleCardClick(card.fileType)}
              className="flex flex-col items-center gap-2 p-4 bg-white rounded-xl border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer"
            >
              <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${card.color}`}>
                <card.icon className="w-6 h-6" aria-hidden />
              </div>
              <span className="text-xs font-medium text-stone-600">{card.label}</span>
            </button>
          ))}
        </div>

                {favFiles.length > 0 && (
          <>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-1.5">
                <Star className="w-3.5 h-3.5 text-amber-500 fill-amber-500" aria-hidden />
                <h2 className="text-sm font-semibold text-stone-500">我的收藏</h2>
              </div>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-8">
              {favFiles.map((file) => {
                const config = getFileTypeConfig(file.nodeType, file.suffix);
                return (
                  <div
                    key={file.id}
                    onClick={() => navigate(file.parentId && file.parentId !== '0' ? `/files/${file.parentId}` : '/files')}
                    className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer group"
                  >
                    <div className="flex-shrink-0">
                      <FileTypeIcon config={config} size="sm" isFolder={file.nodeType === 0} suffix={file.suffix} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-stone-800 truncate">{file.name}</div>
                      <div className="flex items-center gap-2 text-xs text-stone-400 mt-0.5">
                        {file.path && <span className="truncate max-w-[200px]" title={file.path}>{file.path}</span>}
                        {file.fileSize != null && Number(file.fileSize) > 0 && (
                          <>
                            <span className="text-stone-300">·</span>
                            <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                          </>
                        )}
                      </div>
                    </div>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeFavorite(file.id); setFavFiles(getFavorites()); }}
                      className="text-stone-300 hover:text-amber-500 transition-colors opacity-0 group-hover:opacity-100 cursor-pointer"
                      aria-label="取消收藏"
                    >
                      <Star className="w-4 h-4 fill-current" />
                    </button>
                  </div>
                );
              })}
            </div>
          </>
        )}

        {accessedFiles.length > 0 && (
          <>
            <div className="flex items-center justify-between mb-3"><h2 className="text-sm font-semibold text-stone-500">最近访问</h2><button onClick={() => { clearRecentFiles(); setAccessedFiles([]); }} className="text-xs text-stone-400 hover:text-stone-600 cursor-pointer transition-colors">清除记录</button></div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-8">
              {accessedFiles.map((file) => {
                const config = getFileTypeConfig(1, file.suffix);
                return (
                  <div
                    key={file.id}
                    onClick={() => navigate('/files')}
                    className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer"
                  >
                    <div className="flex-shrink-0">
                      <FileTypeIcon config={config} size="sm" isFolder={false} suffix={file.suffix} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-stone-800 truncate">{file.name}</div>
                      <div className="flex items-center gap-2 text-xs text-stone-400 mt-0.5">
                        {file.path && <span className="truncate max-w-[200px]" title={file.path}>{file.path}</span>}
                        {file.fileSize != null && Number(file.fileSize) > 0 && (
                          <>
                            <span className="text-stone-300">·</span>
                            <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        )}

        <h2 className="text-sm font-semibold text-stone-500 mb-3">最近文件</h2>
        {loading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-100">
                <div className="w-8 h-8 bg-stone-100 rounded-lg animate-pulse flex-shrink-0" />
                <div className="flex-1 space-y-1.5">
                  <div className="h-3.5 bg-stone-100 rounded animate-pulse w-2/3" />
                  <div className="h-3 bg-stone-50 rounded animate-pulse w-1/3" />
                </div>
              </div>
            ))}
          </div>
        ) : recentFiles.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-stone-400">
            <FolderClosed className="w-10 h-10 mb-2 opacity-30" aria-hidden />
            <p className="text-sm">暂无文件，开始上传吧</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {recentFiles.map((file) => (
              <div
                key={file.id}
                onClick={() => navigate('/files')}
                className="flex items-center gap-3 p-3 bg-white rounded-lg border border-stone-200 hover:border-primary-300 hover:shadow-sm transition-all cursor-pointer"
              >
                <FileThumbnail file={file} size="sm" />
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium text-stone-800 truncate">{file.name}</div>
                  <div className="flex items-center gap-2 text-xs text-stone-400 mt-0.5">
                    <span className="tabular-nums">{formatSize(Number(file.fileSize))}</span>
                    <span className="text-stone-300">·</span>
                    <span className="flex items-center gap-0.5 tabular-nums">
                      <Clock className="w-3 h-3" aria-hidden />
                      {formatDate(file.updatedAt)}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}