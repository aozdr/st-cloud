import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Search, ChevronRight, Inbox, FolderOpen, Sparkles } from 'lucide-react';
import api from '../lib/api';
import type { SearchResultVO, FileNode } from '../types';
import { getFileTypeConfig, formatSize, formatDate, cn } from '../lib/utils';
import FileTypeIcon from '../components/file/FileTypeIcon';
import PreviewModal from '../components/preview/PreviewModal';

export default function SearchPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const keyword = searchParams.get('keyword') || '';
  const [results, setResults] = useState<SearchResultVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);

  const doSearch = async (kw: string) => {
    if (!kw.trim()) return;
    setLoading(true);
    setSearched(true);
    try {
      const res = await api.get<SearchResultVO[]>('/search', {
        params: { keyword: kw, page: 1, size: 50 },
      });
      setResults(res || []);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (keyword) {
      doSearch(keyword);
    } else {
      setResults([]);
      setSearched(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword]);

  const getParentPath = (path: string): string => {
    if (!path) return '';
    const idx = path.lastIndexOf('/');
    return idx > 0 ? path.substring(0, idx) : '';
  };

  return (
    <div className="flex flex-col h-full">
      {/* Results header */}
      <div className="px-6 py-4 border-b border-stone-200 bg-white flex-shrink-0">
        {keyword ? (
          <div className="flex items-center justify-between">
            <div className="flex items-baseline gap-3">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
                  <Search className="w-4 h-4 text-white" />
                </div>
                <h2 className="text-lg font-semibold text-stone-900">
                  搜索: <span className="text-primary-600">{keyword}</span>
                </h2>
              </div>
              <span className="text-sm text-stone-400">
                {loading ? '搜索中...' : `${results.length} 个结果`}
              </span>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
              <Search className="w-4 h-4 text-white" />
            </div>
            <h2 className="text-lg font-semibold text-stone-900">搜索</h2>
          </div>
        )}
      </div>

      {/* Results */}
      <div className="flex-1 overflow-auto px-6 py-5">
        {loading ? (
          <div className="flex flex-col items-center justify-center h-full text-stone-400">
            <div className="w-8 h-8 border-3 border-primary-200 border-t-primary-600 rounded-full animate-spin mb-4" />
            <p className="text-sm">正在搜索 "{keyword}"...</p>
          </div>
        ) : !searched ? (
          <div className="flex flex-col items-center justify-center h-full text-stone-400">
            <div className="w-20 h-20 rounded-2xl bg-stone-50 flex items-center justify-center mb-4">
              <Search className="w-10 h-10 text-stone-300" strokeWidth={1.2} />
            </div>
            <p className="text-base font-medium text-stone-600">开始你的搜索</p>
            <p className="text-sm mt-2">在顶部搜索框输入关键词，搜索文件名和文档内容</p>
          </div>
        ) : results.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-stone-400">
            <div className="w-20 h-20 rounded-2xl bg-stone-50 flex items-center justify-center mb-4">
              <Inbox className="w-10 h-10 text-stone-300" strokeWidth={1.2} />
            </div>
            <p className="text-base font-medium text-stone-600">未找到匹配结果</p>
            <p className="text-sm mt-2">尝试更换关键词或检查拼写</p>
          </div>
        ) : (
          <div className="max-w-4xl mx-auto divide-y divide-stone-100">
            {results.map((item) => {
              const isFolder = item.nodeType === 0 || (item.nodeType == null && !item.suffix);
              const config = getFileTypeConfig(isFolder ? 0 : 1, item.suffix);
              const parentPath = getParentPath(item.path);

              return (
                <div
                  key={item.fileId}
                  onClick={() => {
                    if (isFolder) {
                      navigate(`/files/${item.fileId}`);
                    } else {
                      const fileNodes: FileNode[] = results
                        .filter(r => !(r.nodeType === 0 || (r.nodeType == null && !r.suffix)))
                        .map(r => ({
                          id: r.fileId,
                          parentId: '',
                          nodeType: 1,
                          name: r.fileName.replace(/<[^>]*>/g, ''),
                          path: r.path,
                          fileSize: r.fileSize,
                          suffix: r.suffix,
                          contentType: r.contentType,
                          status: 0,
                          thumbnailPath: null,
                          createdAt: r.createdAt,
                          updatedAt: r.updatedAt,
                        }));
                      const idx = fileNodes.findIndex(f => f.id === item.fileId);
                      setPreview({ files: fileNodes, index: idx >= 0 ? idx : 0 });
                    }
                  }}
                  className="group flex items-start gap-4 py-3.5 px-2 hover:bg-stone-50 rounded-lg cursor-pointer transition-colors duration-150"
                >
                  {/* Icon */}
                  <div className="flex-shrink-0">
                    <FileTypeIcon config={config} size="lg" isFolder={isFolder} suffix={item.suffix} />
                  </div>

                  {/* Content */}
                  <div className="flex-1 min-w-0">
                    {/* File name with highlight */}
                    <h3 className="text-sm font-semibold text-stone-900 truncate group-hover:text-primary-600 transition-colors">
                      <span className="search-highlight" dangerouslySetInnerHTML={{ __html: item.fileName }} />
                    </h3>

                    {/* Breadcrumb path */}
                    {item.path && (
                      <div className="flex items-center gap-1 mt-1 text-xs text-stone-400">
                        <FolderOpen className="w-3 h-3 flex-shrink-0" />
                        {parentPath && (
                          <>
                            <span className="truncate max-w-[300px]" title={parentPath}>{parentPath}</span>
                            <ChevronRight className="w-3 h-3 flex-shrink-0" />
                          </>
                        )}
                        <span className="text-stone-500 font-medium truncate">{item.fileName}</span>
                      </div>
                    )}

                    {/* Highlight snippet */}
                    {item.highlight && (
                      <div
                        className="search-highlight mt-1.5 text-sm text-stone-600 leading-relaxed line-clamp-2 bg-stone-50 rounded-md px-3 py-2"
                        dangerouslySetInnerHTML={{ __html: item.highlight }}
                      />
                    )}

                    {/* Meta tags */}
                    <div className="flex items-center gap-2.5 mt-2 text-xs text-stone-400">
                      <span className={cn(
                        'inline-flex items-center gap-1 px-2 py-0.5 rounded-md font-medium',
                        isFolder
                          ? 'bg-amber-50 text-amber-600'
                          : 'bg-stone-100 text-stone-500'
                      )}>
                        <Sparkles className="w-3 h-3" />
                        {config.label}
                      </span>
                      {item.fileSize && Number(item.fileSize) > 0 && (
                        <span>{formatSize(item.fileSize)}</span>
                      )}
                      <span className="text-stone-300">·</span>
                      <span>{formatDate(item.updatedAt)}</span>
                    </div>
                  </div>

                  {/* Hover arrow */}
                  <ChevronRight className="w-5 h-5 text-stone-300 flex-shrink-0 mt-2 opacity-0 group-hover:opacity-100 transition-opacity duration-150" />
                </div>
              );
            })}
          </div>
        )}
      </div>

      {preview && (
        <PreviewModal
          files={preview.files}
          currentIndex={preview.index}
          onClose={() => setPreview(null)}
        />
      )}
    </div>
  );
}
