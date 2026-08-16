import { useEffect, useState } from 'react';
import api from '../lib/api';
import type { FileNode, SearchResultVO } from '../types';

/**
 * 当前文件夹内搜索：以全局 folderFilter 关键词调用 /search，
 * 结果限定在当前路径前缀下，返回可直接渲染的 FileNode 列表。
 */
export function useFolderSearch(keyword: string, currentPath: string): FileNode[] {
  const [results, setResults] = useState<FileNode[]>([]);

  useEffect(() => {
    if (!keyword.trim()) {
      setResults([]);
      return;
    }
    let cancelled = false;
    api
      .get<SearchResultVO[]>('/search', { params: { keyword, page: 1, size: 200 } })
      .then((res) => {
        if (cancelled) return;
        const prefix = currentPath === '/' ? '/' : currentPath + '/';
        const filtered = (res || []).filter((r) => {
          const p = r.path || '';
          return p === currentPath || p.startsWith(prefix) || (currentPath === '/' && p.startsWith('/'));
        });
        setResults(
          filtered.map((r) => ({
            id: r.fileId,
            parentId: '',
            nodeType: r.nodeType ?? 1,
            name: r.fileName.replace(/<[^>]*>/g, ''),
            path: r.path,
            fileSize: r.fileSize ?? '0',
            suffix: r.suffix,
            contentType: r.contentType,
            status: 0,
            thumbnailPath: null,
            createdAt: r.createdAt,
            updatedAt: r.updatedAt,
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setResults([]);
      });
    return () => {
      cancelled = true;
    };
  }, [keyword, currentPath]);

  return results;
}
