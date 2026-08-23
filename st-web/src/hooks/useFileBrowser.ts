import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { isElectron } from '../lib/electron';
import api from '../lib/api';
import { useTransferStore } from '../store/transfer';
import type { BlankFileType, FileNode } from '../types';
import type { FileSource } from '../lib/fileSource';
import type { IconSize } from '../components/file/FileToolbar';
import { useMobile } from './useMobile';
import { usePullToRefresh } from './usePullToRefresh';
import { isCapacitor } from '../lib/runtime';
import { pickFromGallery } from '../lib/capacitor';
import { useToast } from '../components/ui/Toast';
import { useConfirm } from '../components/ui/ConfirmDialog';
import { useOperationProgress } from '../components/ui/OperationProgress';
import { useUpload } from './useUpload';
import { usePermission } from '../lib/permission';
import { useDragSelect } from './useDragSelect';
import { useFileKeyboard } from './useFileKeyboard';
import { useFolderFilterStore } from '../store/folderFilter';
import { useFavoritesStore } from '../store/favorites';
import { isEditableOfficeSuffix } from '../lib/editor';
import { useFileSelection } from './useFileSelection';
import { useFileClipboard } from './useFileClipboard';
import { useFileDialogs } from './useFileDialogs';
import { useFolderSearch } from './useFolderSearch';

export interface FileBrowserProps {
  source: FileSource;
  parentId: string | null;
  onNavigateFolder: (node: FileNode) => void;
  onBack?: () => void;
  uploadSpaceId?: string;
  enableShare?: boolean;
  enableVersions?: boolean;
  syncUrl?: boolean;
  categoryLabel?: string;
  focusId?: string | null;
  onOpenDetail?: (node: FileNode) => void;
  /** 页面级详情是否打开（TeamSpacePage 等传入，用于详情打开时列表列数自适应） */
  detailOpen?: boolean;
  onCloseDetail?: () => void;
  onToggleLock?: (action: 'lock' | 'unlock', node: FileNode) => void;
}

/** 节点是否已锁定：以后端锁定字段为准（lockedBy 非空且未过期即视为锁定） */
function isNodeLocked(node: FileNode): boolean {
  if (node.lockedBy == null) return false;
  return node.lockExpireAt == null || new Date(node.lockExpireAt).getTime() > Date.now();
}

/** 各目录滚动位置缓存：组件随目录切换会重挂载，用模块级 Map 跨实例保留 */
const folderScrollPositions: Record<string, number> = {};

/** 每页条数选项（默认 100） */
const PAGE_SIZE_OPTIONS = [50, 100, 150, 200];

export function useFileBrowser({
  source,
  parentId,
  onNavigateFolder,
  onBack,
  uploadSpaceId,
  syncUrl = false,
  focusId,
  onOpenDetail,
  onToggleLock,
}: FileBrowserProps) {
  /** Returns true if the click landed on a file/folder item (not blank area) */
  const isFileItemClick = useCallback((e: React.MouseEvent): boolean => {
    const el = e.target as HTMLElement;
    return !!(el?.closest && el.closest('[data-file-id]'));
  }, []);

  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [files, setFiles] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [view, setView] = useState<'list' | 'grid'>(() => {
    const saved = localStorage.getItem('fileView');
    if (saved === 'grid') return 'grid';
    return 'list';
  });
  const [iconSize, setIconSize] = useState<IconSize>(() => {
    const saved = localStorage.getItem('fileIconSize');
    if (saved === 'sm' || saved === 'lg') return saved;
    return 'md';
  });
  const folderSearch = useFolderFilterStore((s) => s.keyword);
  const setFolderSearch = useFolderFilterStore((s) => s.setKeyword);
  const setFolderPath = useFolderFilterStore((s) => s.setFolderPath);
  const [dragOverFolderId, setDragOverFolderId] = useState<string | null>(null);
  const [zipProgress, setZipProgress] = useState<number | null>(null);
  const [downloadQueuedCount, setDownloadQueuedCount] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const pageRef = useRef(page);
  useEffect(() => {
    pageRef.current = page;
  }, [page]);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [pageSize, setPageSize] = useState<number>(() => {
    const saved = Number(localStorage.getItem('filePageSize'));
    return PAGE_SIZE_OPTIONS.includes(saved) ? saved : 100;
  });
  const [pageInput, setPageInput] = useState('1');
  const [isRefreshing, setIsRefreshing] = useState(false);
  const toggleFav = useFavoritesStore((s) => s.toggleFavorite);
  const checkFav = useFavoritesStore((s) => s.isFavorite);
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

  const { showToast } = useToast();
  const { confirm } = useConfirm();
  const { run: runOperation } = useOperationProgress();
  const { has } = usePermission();
  const isMobile = useMobile();

  const canEditNode = useCallback((node: FileNode) =>
    node.nodeType === 1 && isEditableOfficeSuffix(node.suffix) && has('file:upload'),
  [has]);

  const enableArchive = !onToggleLock;

  const [currentPath, setCurrentPath] = useState('/');
  const folderSearchResults = useFolderSearch(folderSearch, currentPath);

  const sortedFiles = useMemo(() => {
    const arr = [...files];
    arr.sort((a, b) => {
      if (foldersFirst && a.nodeType !== b.nodeType) return a.nodeType === 0 ? -1 : 1;
      const aFav = checkFav(a.id) ? 1 : 0;
      const bFav = checkFav(b.id) ? 1 : 0;
      if (aFav !== bFav) return bFav - aFav;
      let cmp = 0;
      if (sortBy === 'name') {
        cmp = a.name.localeCompare(b.name, 'zh-CN');
      } else if (sortBy === 'size') {
        cmp = (Number(a.fileSize || 0) - Number(b.fileSize || 0));
      } else {
        cmp = (new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime());
      }
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return arr;
  }, [files, sortBy, sortDir, foldersFirst, checkFav]);

  const filteredFiles = useMemo(() => {
    if (!folderSearch.trim()) return sortedFiles;
    return folderSearchResults;
  }, [sortedFiles, folderSearch, folderSearchResults]);

  const selection = useFileSelection(filteredFiles, isMobile);
  const {
    selectedIds, setSelectedIds, setFocusedIndex, setLastSelectedId,
    focusedIndex, lastSelectedId, mobileSelectMode, setMobileSelectMode,
    handleSelect, toggleSelect, selectAll, clearSelection, moveFocus,
    handleMobileLongPress, handleMobileClick,
  } = selection;

  const { clipboard, setClipboard, paste } = useFileClipboard(source, parentId, showToast, () => fetchFiles());

  const dialogs = useFileDialogs();
  const {
    showCreateFolder, setShowCreateFolder,
    newFileType, setNewFileType,
    showBatchRename, setShowBatchRename,
    archiveTarget, setArchiveTarget,
    renameTarget, setRenameTarget,
    convertTarget, setConvertTarget,
    moveTarget, setMoveTarget,
    shareTarget, setShareTarget,
    downloadTarget, setDownloadTarget,
    versionTarget, setVersionTarget,
    preview, setPreview,
    contextMenu, setContextMenu,
    blankContextMenu, setBlankContextMenu,
  } = dialogs;

  const fileListRef = useRef<HTMLDivElement>(null);
  const { dragRect, startDrag } = useDragSelect(fileListRef, setSelectedIds);

  const pendingPreviewIdRef = useRef<string | null>(null);
  useEffect(() => {
    const st = (location.state ?? null) as { openPreview?: string } | null;
    if (st?.openPreview) {
      pendingPreviewIdRef.current = st.openPreview;
      navigate(location.pathname + location.search, { replace: true, state: null });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [isDragging, setIsDragging] = useState(false);
  const isInternalDragRef = useRef(false);
  const prevParentId = useRef<string | null>(null);

  const stateRef = useRef({ selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId });

  const [pathEditMode, setPathEditMode] = useState(false);
  const [pathInput, setPathInput] = useState('/');
  const [pathError, setPathError] = useState(false);
  const pathInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const bandRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    stateRef.current = { selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId };
  }, [selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId]);
  const { addFiles, addFilePaths, refreshSignal } = useUpload();

  const hasFilesRef = useRef(false);
  useEffect(() => {
    hasFilesRef.current = files.length > 0;
  }, [files]);

  const fetchFiles = useCallback(async (pageToUse?: number) => {
    const targetPage = pageToUse ?? pageRef.current;
    if (!hasFilesRef.current) setLoading(true);
    try {
      const res = await source.listFiles(parentId, targetPage, pageSize);
      const records = res?.records || [];
      setFiles(records);
      setLoadError(false);
      setTotal(Number(res?.total || 0));
      const pendingId = pendingPreviewIdRef.current;
      if (pendingId) {
        pendingPreviewIdRef.current = null;
        const fileFiles = records.filter((f) => f.nodeType === 1);
        const idx = fileFiles.findIndex((f) => f.id === pendingId);
        if (idx >= 0) setPreview({ files: fileFiles, index: idx });
      }
    } catch {
      setFiles([]);
      setLoadError(true);
    } finally {
      setLoading(false);
      setIsRefreshing(false);
    }
  }, [source, parentId, pageSize, setPreview]);

  useEffect(() => {
    const isFolderChange = prevParentId.current !== parentId;
    prevParentId.current = parentId;
    if (isFolderChange) {
      setLoading(true);
      setPage(1);
      fetchFiles(1);
    } else {
      fetchFiles();
    }
    clearSelection();
    setFocusedIndex(-1);
    setFolderSearch('');
    setDetailFile(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchFiles, refreshKey]);

  useEffect(() => {
    if (!focusId || loading || files.length === 0) return;
    if (!files.some((f) => f.id === focusId)) return;
    setSelectedIds(new Set([focusId]));
    const el = fileListRef.current?.querySelector(`[data-file-id="${focusId}"]`);
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [focusId, loading, files, setSelectedIds]);

  useEffect(() => {
    if (!parentId || parentId === '0' || parentId === 'null') {
      setCurrentPath('/');
      return;
    }
    const st = (location.state ?? null) as { nodeId?: string; nodePath?: string } | null;
    if (st?.nodeId === parentId && st.nodePath) {
      setCurrentPath(st.nodePath);
      return;
    }
    let cancelled = false;
    source.getNodeById(parentId).then((node) => {
      if (!cancelled && node) setCurrentPath(node.path || '/');
    }).catch(() => {
      if (!cancelled) setCurrentPath('/');
    });
    return () => { cancelled = true; };
  }, [source, parentId, location.state]);

  useEffect(() => {
    if (refreshSignal > 0) setRefreshKey((k) => k + 1);
  }, [refreshSignal]);

  useEffect(() => {
    setPageInput(String(page));
  }, [page]);

  useEffect(() => {
    localStorage.setItem('fileView', view);
  }, [view]);

  useEffect(() => {
    localStorage.setItem('fileIconSize', iconSize);
  }, [iconSize]);

  useEffect(() => {
    const reset = () => { isInternalDragRef.current = false; };
    document.addEventListener('dragend', reset);
    return () => document.removeEventListener('dragend', reset);
  }, []);

  useEffect(() => {
    const el = fileListRef.current;
    if (!el) return;
    const handleScroll = () => {
      if (parentId) folderScrollPositions[parentId] = el.scrollTop;
    };
    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => el.removeEventListener('scroll', handleScroll);
  }, [parentId]);

  useEffect(() => {
    if (loading || files.length === 0) return;
    const el = fileListRef.current;
    const saved = parentId ? folderScrollPositions[parentId] : undefined;
    if (el && saved != null) el.scrollTop = saved;
  }, [loading, parentId, files]);

  useEffect(() => {
    if (isMobile && mobileSelectMode && selectedIds.size === 0) {
      setMobileSelectMode(false);
    }
  }, [isMobile, mobileSelectMode, selectedIds.size, setMobileSelectMode]);

  const ptr = usePullToRefresh({ onRefresh: async () => { await fetchFiles(); } });

  const refresh = useCallback(() => {
    setIsRefreshing(true);
    setRefreshKey((k) => k + 1);
  }, []);

  useEffect(() => {
    if (!isElectron()) return;
    const unsubscribe = window.electronAPI?.onRefreshFileList?.(() => refresh());
    return () => unsubscribe?.();
  }, [refresh]);

  const handleSortChange = useCallback((col: 'name' | 'size' | 'time') => {
    if (col === sortBy) {
      const next = sortDir === 'asc' ? 'desc' : 'asc';
      setSortDir(next);
      localStorage.setItem('fileSortDir', next);
    } else {
      setSortBy(col);
      localStorage.setItem('fileSortBy', col);
    }
  }, [sortBy, sortDir]);

  const handlePageSizeChange = useCallback((v: number) => {
    setPageSize(v);
    setPage(1);
    fetchFiles(1);
    try { localStorage.setItem('filePageSize', String(v)); } catch { /* ignore */ }
  }, [fetchFiles]);

  const handlePageInputCommit = useCallback(() => {
    const n = parseInt(pageInput, 10);
    const tp = Math.max(1, Math.ceil(total / pageSize));
    if (Number.isNaN(n) || n < 1) {
      setPageInput(String(page));
      return;
    }
    const target = Math.min(n, tp);
    setPage(target);
    setPageInput(String(target));
    fetchFiles(target);
  }, [pageInput, page, total, pageSize, fetchFiles]);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    if (isInternalDragRef.current || e.dataTransfer.types.includes('application/x-file-ids')) return;
    const isFileDrag = e.dataTransfer.types.includes('Files') || e.dataTransfer.files.length > 0;
    if (!isFileDrag) return;
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    if (isInternalDragRef.current || e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    if (e.currentTarget === e.target) setIsDragging(false);
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    if (isInternalDragRef.current || e.dataTransfer.types.includes('application/x-file-ids')) {
      setIsDragging(false);
      return;
    }
    e.preventDefault();
    setIsDragging(false);
    const droppedFiles = Array.from(e.dataTransfer.files);
    if (droppedFiles.length === 0) return;
    if (isElectron()) {
      const filePaths = droppedFiles
        .map((f) => (f as File & { path?: string }).path)
        .filter((p): p is string => !!p);
      if (filePaths.length > 0) {
        addFilePaths(filePaths, parentId || '0', undefined, uploadSpaceId);
        return;
      }
    }
    addFiles(droppedFiles, parentId || '0', undefined, uploadSpaceId);
  }, [addFilePaths, addFiles, parentId, uploadSpaceId]);

  const handleItemDragStart = useCallback((e: React.DragEvent, node: FileNode) => {
    isInternalDragRef.current = true;
    const ids = selectedIds.has(node.id) ? [...selectedIds] : [node.id];
    e.dataTransfer.setData('application/x-file-ids', JSON.stringify(ids));
    e.dataTransfer.effectAllowed = 'move';
  }, [selectedIds]);

  const handleFolderDragOver = useCallback((e: React.DragEvent, folder: FileNode) => {
    if (!isInternalDragRef.current && !e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'move';
    setDragOverFolderId(folder.id);
  }, []);

  const handleFolderDragLeave = useCallback((e: React.DragEvent, folder: FileNode) => {
    e.stopPropagation();
    setDragOverFolderId((prev) => (prev === folder.id ? null : prev));
  }, []);

  const handleFolderDrop = useCallback(async (e: React.DragEvent, folder: FileNode) => {
    const data = e.dataTransfer.getData('application/x-file-ids');
    if (!isInternalDragRef.current && !data) return;
    e.preventDefault();
    e.stopPropagation();
    setDragOverFolderId(null);
    const ids = JSON.parse(data) as string[];
    if (ids.includes(folder.id)) return;
    try {
      await source.move(ids, folder.id);
      showToast('\u79fb\u52a8\u6210\u529f', 'success');
      fetchFiles();
    } catch {
      showToast('\u79fb\u52a8\u5931\u8d25', 'error');
    }
  }, [source, showToast, fetchFiles]);

  const handleUploadClick = useCallback(async () => {
    if (isCapacitor()) {
      try {
        const files = await pickFromGallery();
        if (files && files.length > 0) {
          addFiles(files, parentId || '0', undefined, uploadSpaceId);
        }
      } catch (e) {
        showToast(e instanceof Error ? e.message : '选择照片失败', 'error');
      }
      return;
    }
    fileInputRef.current?.click();
  }, [addFiles, parentId, uploadSpaceId, showToast]);

  const handleUploadChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    e.target.value = '';
    if (selected.length === 0) return;
    if (isElectron()) {
      const filePaths = selected
        .map((f) => (f as File & { path?: string }).path)
        .filter((p): p is string => !!p);
      if (filePaths.length > 0) {
        addFilePaths(filePaths, parentId || '0', undefined, uploadSpaceId);
        return;
      }
    }
    addFiles(selected, parentId || '0', undefined, uploadSpaceId);
  }, [addFilePaths, addFiles, parentId, uploadSpaceId]);

  const handleNewFile = useCallback((type: BlankFileType) => {
    setNewFileType(type);
  }, [setNewFileType]);

  const handleCreateFile = useCallback(async (type: BlankFileType, fileName: string) => {
    try {
      const node = await source.createBlankFile(parentId, type, fileName);
      showToast('新建成功');
      fetchFiles();
      if (node && node.nodeType === 1 && isEditableOfficeSuffix(node.suffix)) {
        navigate(`/file/${node.id}/editor`, { state: { from: location.pathname + location.search } });
      }
    } catch (err) {
      console.error('Create blank file failed:', err);
      showToast(err instanceof Error ? err.message : '新建失败', 'error');
    } finally {
      setNewFileType(null);
    }
  }, [source, parentId, showToast, fetchFiles, navigate, location.pathname, location.search, setNewFileType]);

  const handleContextMenu = useCallback((e: React.MouseEvent, node: FileNode) => {
    e.preventDefault();
    if (isMobile) {
      handleMobileLongPress(node);
      return;
    }
    if (!selectedIds.has(node.id)) {
      setSelectedIds(new Set([node.id]));
      setLastSelectedId(node.id);
    }
    setBlankContextMenu(null);
    setContextMenu({ x: e.clientX, y: e.clientY, node });
  }, [isMobile, handleMobileLongPress, selectedIds, setSelectedIds, setLastSelectedId, setBlankContextMenu, setContextMenu]);

  const focusedId = focusedIndex >= 0 && focusedIndex < files.length ? files[focusedIndex].id : null;

  const handleDeleteRef = useRef<(nodeIds: string[]) => void>(() => {});
  handleDeleteRef.current = async (nodeIds: string[]) => {
    const confirmed = await confirm({
      title: '删除文件',
      message: `确定删除选中的 ${nodeIds.length} 个文件？文件将移入回收站。`,
      confirmText: '删除',
      danger: true,
    });
    if (!confirmed) return;
    await runOperation('删除中', async () => {
      try {
        await source.delete(nodeIds);
        showToast(`已删除 ${nodeIds.length} 项`);
        clearSelection();
        fetchFiles();
      } catch (err) {
        console.error('Delete failed:', err);
        showToast('删除失败', 'error');
      }
    });
  };

  useFileKeyboard(
    () => stateRef.current,
    {
      setSelectedIds, setClipboard, setFocusedIndex, setLastSelectedId,
      setRenameTarget, setPreview, setContextMenu, setBlankContextMenu, setShowCreateFolder,
      selectAll, clearSelection, moveFocus, refresh, navigate,
      onBack, onNavigateFolder,
      handlePaste: paste,
      handleDelete: (ids: string[]) => handleDeleteRef.current(ids),
      showToast, hasPermission: has,
    },
    !isMobile,
  );

  const handleDownload = useCallback(async (nodeIds: string[]) => {
    try {
      if (isElectron()) {
        const nodes = nodeIds
          .map((id) => files.find((f) => f.id === id))
          .filter((n): n is FileNode => !!n);
        const fileNodes = nodes.filter((n) => n.nodeType === 1);
        if (nodes.length > 0 && fileNodes.length === nodes.length) {
          if (nodes.length === 1) {
            setDownloadTarget(nodes[0]);
          } else {
            const downloadsDir = await window.electronAPI!.getDownloadsPath();
            const dir = downloadsDir || '';
            for (const n of nodes) {
              await window.electronAPI!.startDownload(n.id, n.name, Number(n.fileSize || 0), `${dir}\\${n.name}`);
            }
            setDownloadQueuedCount(nodes.length);
          }
          return;
        }
      }
      if (nodeIds.length === 1) {
        const node = files.find((f) => f.id === nodeIds[0]);
        const dlLimit = useTransferStore.getState().effective.downloadSpeedLimit;
        const url = await source.getDownloadUrl(nodeIds[0]);
        const sep = url.includes('?') ? '&' : '?';
        const finalUrl = dlLimit > 0 ? `${url}${sep}clientLimit=${dlLimit}` : url;
        const a = document.createElement('a');
        a.href = finalUrl;
        a.download = node?.name || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      } else {
        setZipProgress(0);
        try {
          showToast('正在打包下载，请稍候…', 'info');
          const blob = await source.downloadZip(nodeIds, (loaded) => setZipProgress(loaded));
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'download.zip';
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
          showToast('打包完成，已开始下载', 'success');
        } finally {
          setZipProgress(null);
        }
      }
    } catch {
      showToast('下载失败', 'error');
    }
  }, [files, source, showToast, setDownloadTarget]);

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

  const handleEdit = useCallback((node: FileNode) => {
    navigate(`/file/${node.id}/editor`, { state: { from: location.pathname + location.search } });
  }, [navigate, location.pathname, location.search]);

  const [detailFile, setDetailFile] = useState<FileNode | null>(null);

  const handleContextAction = useCallback(async (action: string, node: FileNode) => {
    setContextMenu(null);
    switch (action) {
      case 'open': onNavigateFolder(node); break;
      case 'preview': {
        const fileFiles = files.filter((f) => f.nodeType === 1);
        const idx = fileFiles.findIndex((f) => f.id === node.id);
        if (idx >= 0) setPreview({ files: fileFiles, index: idx });
        break;
      }
      case 'edit': handleEdit(node); break;
      case 'textEdit':
        navigate(`/file/${node.id}/text-editor`, {
          state: { from: location.pathname + location.search, name: node.name, spaceId: uploadSpaceId ?? undefined },
        });
        break;
      case 'archive': setArchiveTarget(node); break;
      case 'convert': setConvertTarget(node); break;
      case 'cut':
        setClipboard({ nodeIds: [...selectedIds], mode: 'cut' });
        showToast(`已剪切 ${selectedIds.size} 项`);
        break;
      case 'copy':
        setClipboard({ nodeIds: [...selectedIds], mode: 'copy' });
        showToast(`已复制 ${selectedIds.size} 项`);
        break;
      case 'paste': paste(); break;
      case 'rename': setRenameTarget(node); break;
      case 'download': {
        const ids = selectedIds.has(node.id) ? [...selectedIds] : [node.id];
        handleDownload(ids);
        break;
      }
      case 'moveTo':
        setMoveTarget({ nodeIds: selectedIds.has(node.id) ? [...selectedIds] : [node.id], mode: 'move' });
        break;
      case 'copyTo':
        setMoveTarget({ nodeIds: selectedIds.has(node.id) ? [...selectedIds] : [node.id], mode: 'copy' });
        break;
      case 'delete': {
        const ids = selectedIds.has(node.id) ? [...selectedIds] : [node.id];
        handleDeleteRef.current(ids);
        break;
      }
      case 'share': setShareTarget(node); break;
      case 'versions': setVersionTarget(node); break;
      case 'details':
        if (onOpenDetail) onOpenDetail(node);
        else setDetailFile(node);
        break;
      case 'resetEditing':
        await api.post(`/file/${node.id}/editor/reset`);
        showToast('已重置编辑状态');
        fetchFiles();
        break;
      case 'favorite': {
        const added = await toggleFav(node);
        showToast(added ? '已收藏' : '已取消收藏');
        break;
      }
      case 'hide': {
        await api.put(`/file/${node.id}/hide`);
        showToast('已隐藏');
        fetchFiles();
        break;
      }
      case 'lock':
      case 'unlock':
        await onToggleLock?.(action as 'lock' | 'unlock', node);
        fetchFiles();
        break;
    }
  }, [
    files, handleEdit, navigate, location.pathname, location.search, uploadSpaceId,
    setArchiveTarget, setConvertTarget, setClipboard, selectedIds, showToast, paste,
    setRenameTarget, handleDownload, setMoveTarget, handleDeleteRef, setShareTarget,
    setVersionTarget, onOpenDetail, setDetailFile, toggleFav, fetchFiles, onToggleLock,
    onNavigateFolder, setContextMenu, setPreview,
  ]);

  const handleToggleFavorite = useCallback(async (node: FileNode) => {
    const added = await toggleFav(node);
    showToast(added ? '已收藏' : '已取消收藏');
  }, [toggleFav, showToast]);

  const selectedSize = useMemo(
    () => files.reduce((sum, f) => selectedIds.has(f.id) ? sum + Number(f.fileSize || 0) : sum, 0),
    [files, selectedIds],
  );

  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const editableSelected = useMemo(() => {
    if (selectedIds.size !== 1) return null;
    const node = files.find((f) => selectedIds.has(f.id));
    return node && canEditNode(node) ? node : null;
  }, [selectedIds, files, canEditNode]);

  useEffect(() => {
    setFolderPath(currentPath);
  }, [currentPath, setFolderPath]);

  const lockedIds = useMemo(() => {
    const set = new Set<string>();
    for (const f of files) {
      if (isNodeLocked(f)) set.add(f.id);
    }
    return set;
  }, [files]);

  const handleToolbarEdit = useCallback(() => {
    if (editableSelected) handleEdit(editableSelected);
  }, [editableSelected, handleEdit]);

  const handleToolbarSortChange = useCallback((v: 'name' | 'size' | 'time') => {
    setSortBy(v);
    localStorage.setItem('fileSortBy', v);
  }, [setSortBy]);

  const handleSortDirToggle = useCallback(() => {
    const next = sortDir === 'asc' ? 'desc' : 'asc';
    setSortDir(next);
    localStorage.setItem('fileSortDir', next);
  }, [sortDir]);

  const handleNewFolderClick = useCallback(() => setShowCreateFolder(true), [setShowCreateFolder]);

  const handleBatchRenameClick = useCallback(() => setShowBatchRename(true), [setShowBatchRename]);

  const handleToolbarDownload = useCallback(() => {
    handleDownload([...selectedIds]);
  }, [handleDownload, selectedIds]);

  const handleToolbarMove = useCallback(() => {
    setMoveTarget({ nodeIds: [...selectedIds], mode: 'move' });
  }, [selectedIds, setMoveTarget]);

  const handleToolbarCopy = useCallback(() => {
    setMoveTarget({ nodeIds: [...selectedIds], mode: 'copy' });
  }, [selectedIds, setMoveTarget]);

  const handleToolbarDelete = useCallback(() => {
    handleDeleteRef.current([...selectedIds]);
  }, [selectedIds]);

  const handleToggleFoldersFirst = useCallback((v: boolean) => {
    setFoldersFirst(v);
    localStorage.setItem('fileFoldersFirst', String(v));
  }, [setFoldersFirst]);

  const handleListNavigate = useCallback((node: FileNode) => {
    if (isMobile && mobileSelectMode) {
      handleMobileClick(node);
      return;
    }
    if (node.nodeType === 0) onNavigateFolder(node);
  }, [isMobile, mobileSelectMode, handleMobileClick, onNavigateFolder]);

  const handleListDoubleClick = useCallback((node: FileNode) => {
    if (node.nodeType === 0) {
      onNavigateFolder(node);
    } else if (has('file:preview')) {
      const fileFiles = files.filter((f) => f.nodeType === 1);
      const idx = fileFiles.findIndex((f) => f.id === node.id);
      setPreview({ files: fileFiles, index: idx >= 0 ? idx : 0 });
    }
  }, [files, has, onNavigateFolder, setPreview]);

  const handlePrevPage = useCallback(() => {
    const np = Math.max(1, page - 1);
    setPage(np);
    fetchFiles(np);
  }, [page, fetchFiles]);

  const handleNextPage = useCallback(() => {
    const np = Math.min(totalPages, page + 1);
    setPage(np);
    fetchFiles(np);
  }, [page, totalPages, fetchFiles]);

  return {
    files, loading, loadError, view, setView, iconSize, setIconSize,
    dragOverFolderId, zipProgress, downloadQueuedCount, setDownloadQueuedCount,
    page, total, refreshKey, pageSize, pageInput, setPageInput, isRefreshing,
    sortBy, sortDir, foldersFirst, currentPath, pathSegments,
    filteredFiles, selectedIds, focusedId, clipboard,
    dragRect, detailFile, setDetailFile, allSelected, selectedSize,
    editableSelected, totalPages, lockedIds, isDragging,
    pathEditMode, setPathEditMode, pathInput, setPathInput, pathError, setPathError,
    mobileSelectMode, setMobileSelectMode, ptr, enableArchive,
    isMobile, has, checkFav, showToast, toggleSelect, handleSelect,
    selectAll, clearSelection, paste,
    fileListRef, bandRef, fileInputRef, pathInputRef,
    fetchFiles, refresh, handleDragOver, handleDragLeave, handleDrop,
    handleUploadClick, handleUploadChange,
    handleItemDragStart, handleFolderDragOver, handleFolderDragLeave, handleFolderDrop,
    handleSortChange, handlePageSizeChange, handlePageInputCommit, handleContextMenu,
    handleContextAction, handleToggleFavorite, handleDownload, handleArchiveExtracted,
    handleCreateFile, enterPathEditMode, handlePathSubmit, navigateToPath, handleEdit,
    handleToolbarEdit, handleToolbarSortChange, handleSortDirToggle, handleNewFolderClick,
    handleBatchRenameClick, handleToolbarDownload, handleToolbarMove, handleToolbarCopy,
    handleToolbarDelete, handleToggleFoldersFirst, handleListNavigate, handleListDoubleClick,
    handlePrevPage, handleNextPage, handleNewFile, handleDeleteRef,
    isFileItemClick, startDrag,
    showCreateFolder, setShowCreateFolder, newFileType, setNewFileType,
    showBatchRename, setShowBatchRename, archiveTarget, setArchiveTarget,
    renameTarget, setRenameTarget, convertTarget, setConvertTarget,
    moveTarget, setMoveTarget, shareTarget, setShareTarget,
    downloadTarget, setDownloadTarget, versionTarget, setVersionTarget,
    preview, setPreview, contextMenu, setContextMenu, blankContextMenu, setBlankContextMenu,
  };
}
