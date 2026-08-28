import { useState, useRef, useCallback, useMemo } from 'react';
import type { FileNode } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import type { useToast } from '../../components/ui/Toast';

type ShowToast = ReturnType<typeof useToast>['showToast'];

/**
 * 文件浏览路径导航模块：路径栏编辑、按路径跳转、面包屑分段、解压后跳转。
 * 拆分自 useFileBrowser.ts，行为保持不变。
 */
export function usePathNavigation({
  source, onNavigateFolder, showToast, currentPath,
}: {
  source: FileSource;
  onNavigateFolder: (node: FileNode) => void;
  showToast: ShowToast;
  currentPath: string;
}) {
  const [pathEditMode, setPathEditMode] = useState(false);
  const [pathInput, setPathInput] = useState('/');
  const [pathError, setPathError] = useState(false);
  const pathInputRef = useRef<HTMLInputElement>(null);

  /** 解压完成后跳转到解压目录（folderId 失效时回退根目录） */
  const handleArchiveExtracted = useCallback(async (folderId: string) => {
    const fallbackNode: FileNode = {
      id: folderId, parentId: '0', nodeType: 0, name: '', path: '/',
      fileSize: null, suffix: null, contentType: null, status: 0,
      thumbnailPath: null, createdAt: '', updatedAt: '',
    };
    try {
      if (!folderId || folderId === '0') {
        onNavigateFolder({
          id: '0', parentId: '0', nodeType: 0, name: '', path: '/',
          fileSize: null, suffix: null, contentType: null, status: 0,
          thumbnailPath: null, createdAt: '', updatedAt: '',
        });
        return;
      }
      const node = await source.getNodeById(folderId);
      if (node && node.nodeType === 0) {
        onNavigateFolder(node);
        return;
      }
    } catch {
      // 节点查询失败时按 id 直接跳转
    }
    onNavigateFolder(fallbackNode);
  }, [source, onNavigateFolder]);

  const enterPathEditMode = useCallback(() => {
    setPathInput(currentPath === '/' ? '/' : currentPath);
    setPathError(false);
    setPathEditMode(true);
    setTimeout(() => pathInputRef.current?.select(), 0);
  }, [currentPath]);

  const navigateToPath = useCallback(async (path: string) => {
    if (path === '/' || path === '') {
      onNavigateFolder({ id: '0', parentId: '0', nodeType: 0, name: '', path: '/', fileSize: null, suffix: null, contentType: null, status: 0, thumbnailPath: null, createdAt: '', updatedAt: '' });
      return;
    }
    try {
      const node = await source.resolveByPath(path);
      if (node && node.nodeType === 0) onNavigateFolder(node);
    } catch {
      showToast('路径不存在', 'error');
    }
  }, [source, showToast, onNavigateFolder]);

  const handlePathSubmit = useCallback(async () => {
    const raw = pathInput.trim().replace(/\\/g, '/').replace(/\/$/, '');
    if (!raw || raw === '/') {
      setPathEditMode(false);
      navigateToPath('/');
      return;
    }
    try {
      const node = await source.resolveByPath(raw);
      if (node && node.nodeType === 0) {
        setPathError(false);
        setPathEditMode(false);
        onNavigateFolder(node);
      } else if (node && node.nodeType === 1) {
        setPathError(true);
        showToast('该路径指向的是文件而非文件夹', 'error');
      } else {
        setPathError(true);
        showToast('路径不存在', 'error');
      }
    } catch {
      setPathError(true);
      showToast('路径不存在: ' + raw, 'error');
    }
  }, [pathInput, source, showToast, onNavigateFolder, navigateToPath]);

  const pathSegments = useMemo(() => {
    const cleanPath = currentPath.replace(/^\/+|\/+$/g, '');
    if (!cleanPath) return [];
    const parts = cleanPath.split('/');
    const segments: { name: string; path: string }[] = [];
    let acc = '';
    for (const part of parts) {
      if (!part) continue;
      acc += '/' + part;
      segments.push({ name: part, path: acc });
    }
    return segments;
  }, [currentPath]);

  return {
    pathEditMode, setPathEditMode,
    pathInput, setPathInput,
    pathError, setPathError,
    pathInputRef,
    handleArchiveExtracted,
    enterPathEditMode,
    navigateToPath,
    handlePathSubmit,
    pathSegments,
  };
}
