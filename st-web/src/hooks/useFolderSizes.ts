import { useEffect, useState } from 'react';
import api from '../lib/api';
import type { FileNode } from '../types';

export interface FolderSizeInfo {
  size: number;
  fileCount: number;
  folderCount: number;
}

/**
 * 批量懒加载当前列表中文件夹的大小统计（子树聚合）。
 * files 变化后去抖 500ms 统一请求，避免翻页/筛选时频繁调用。
 */
export function useFolderSizes(files: FileNode[]): Map<string, FolderSizeInfo> {
  const [sizes, setSizes] = useState<Map<string, FolderSizeInfo>>(new Map());

  useEffect(() => {
    const folderIds = files.filter((f) => f.nodeType === 0).map((f) => f.id);
    if (folderIds.length === 0) {
      setSizes(new Map());
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await api.post<Record<string, FolderSizeInfo>>('/file/folder-sizes', { ids: folderIds });
        const map = new Map(Object.entries(res || {}).map(([k, v]) => [k, v]));
        setSizes(map);
      } catch {
        setSizes(new Map());
      }
    }, 500);
    return () => clearTimeout(timer);
  }, [files]);

  return sizes;
}
