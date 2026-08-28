import { useState, useEffect } from 'react';
import type { SetURLSearchParams } from 'react-router-dom';
import type { FileNode } from '../../types';
import type { IconSize } from '../../components/file/FileToolbar';

/**
 * 文件浏览偏好模块：视图/图标尺寸/分页/排序等用户偏好的持久化状态。
 * 拆分自 useFileBrowser.ts，行为保持不变（localStorage 键名不变）。
 */

/** 节点是否已锁定：以后端锁定字段为准（lockedBy 非空且未过期即视为锁定） */
export function isNodeLocked(node: FileNode): boolean {
  if (node.lockedBy == null) return false;
  return node.lockExpireAt == null || new Date(node.lockExpireAt).getTime() > Date.now();
}

/** 各目录滚动位置缓存：组件随目录切换会重挂载，用模块级 Map 跨实例保留 */
export const folderScrollPositions: Record<string, number> = {};

/** 每页条数选项（默认 100） */
export const PAGE_SIZE_OPTIONS = [50, 100, 150, 200];

/**
 * 浏览偏好状态组：跨会话持久化（localStorage），排序偏好可同步到 URL query。
 * 主 hook 解构使用，变量名与拆分前一致，调用方零改动。
 */
export function useBrowserPreferences(
  syncUrl: boolean,
  searchParams: URLSearchParams,
  setSearchParams: SetURLSearchParams,
) {
  const [view, setView] = useState<'list' | 'grid' | 'waterfall'>(() => {
    const saved = localStorage.getItem('fileView');
    if (saved === 'grid' || saved === 'waterfall') return saved;
    return 'list';
  });
  const [iconSize, setIconSize] = useState<IconSize>(() => {
    const saved = localStorage.getItem('fileIconSize');
    if (saved === 'sm' || saved === 'lg') return saved;
    return 'md';
  });
  const [pageSize, setPageSize] = useState<number>(() => {
    const saved = Number(localStorage.getItem('filePageSize'));
    return PAGE_SIZE_OPTIONS.includes(saved) ? saved : 100;
  });
  const [sortBy, setSortBy] = useState<'name' | 'size' | 'time'>(() => {
    if (syncUrl) {
      const s = searchParams.get('sort');
      if (s === 'name' || s === 'size' || s === 'time') return s;
    }
    return (localStorage.getItem('fileSortBy') as 'name' | 'size' | 'time') || 'name';
  });
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>(() => {
    if (syncUrl) {
      const d = searchParams.get('dir');
      if (d === 'asc' || d === 'desc') return d;
    }
    return (localStorage.getItem('fileSortDir') as 'asc' | 'desc') || 'asc';
  });
  const [foldersFirst, setFoldersFirst] = useState<boolean>(() => {
    return localStorage.getItem('fileFoldersFirst') !== 'false';
  });

  // 视图偏好持久化
  useEffect(() => {
    localStorage.setItem('fileView', view);
  }, [view]);

  useEffect(() => {
    localStorage.setItem('fileIconSize', iconSize);
  }, [iconSize]);

  // 排序偏好同步到 URL（分享页等场景），replace 不产生历史记录
  useEffect(() => {
    if (!syncUrl) return;
    setSearchParams(
      (prev) => {
        if (prev.get('sort') === sortBy && prev.get('dir') === sortDir) return prev;
        prev.set('sort', sortBy);
        prev.set('dir', sortDir);
        return prev;
      },
      { replace: true },
    );
  }, [sortBy, sortDir, syncUrl, setSearchParams]);

  return {
    view, setView,
    iconSize, setIconSize,
    pageSize, setPageSize,
    sortBy, setSortBy,
    sortDir, setSortDir,
    foldersFirst, setFoldersFirst,
  };
}
