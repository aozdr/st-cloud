import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { isElectron } from '../../lib/electron';
import api from '../../lib/api';
import { useTransferStore } from '../../store/transfer';
import type { FileNode, SearchResultVO } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import FileTable from './FileTable';
import FileGrid from './FileGrid';
import ContextMenu from './ContextMenu';
import BlankContextMenu from './BlankContextMenu';
import MoveDialog from './MoveDialog';
import DownloadDialog from './DownloadDialog';
import { CreateFolderDialog, RenameDialog, EmptyState, FileListSkeleton } from './Dialogs';
import FileDetailPanel from './FileDetailPanel';
import PreviewModal from '../preview/PreviewModal';
import ShareDialog from '../share/ShareDialog';
import VersionHistoryDialog from './VersionHistoryDialog';
import { useToast } from '../ui/Toast';
import { useConfirm } from '../ui/ConfirmDialog';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import { useUpload } from '../../hooks/useUpload';
import { formatSize, cn } from '../../lib/utils';
import { usePermission } from '../../lib/permission';
import { useDragSelect } from '../../hooks/useDragSelect';
import { useFileKeyboard } from '../../hooks/useFileKeyboard';
import { useFolderFilterStore } from '../../store/folderFilter';
import { toggleFavorite, isFavorite } from '../../lib/favorites';
import { List, LayoutGrid, FolderPlus, Download, Trash2, Copy, FolderInput, X, RefreshCw, ArrowDownUp, Home, ChevronRight, MapPin, Upload, ArrowUp, Pencil } from 'lucide-react';

export interface FileBrowserProps {
  source: FileSource;
  parentId: string | null;
  onNavigateFolder: (node: FileNode) => void;
  onBack?: () => void;
  uploadSpaceId?: string;
  enableShare?: boolean;
  enableVersions?: boolean;
  syncUrl?: boolean;
}

export default function FileBrowser({
  source,
  parentId,
  onNavigateFolder,
  onBack,
  uploadSpaceId,
  enableShare = true,
  enableVersions = true,
  syncUrl = false,
}: FileBrowserProps) {

  /** Returns true if the click landed on a file/folder item (not blank area) */
  const isFileItemClick = (e: React.MouseEvent): boolean => {
    const el = e.target as HTMLElement;
    return !!(el?.closest && el.closest('[data-file-id]'));
  };


  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [files, setFiles] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<'list' | 'grid'>(() => {
    return (localStorage.getItem('fileView') as 'list' | 'grid') || 'list';
  });
  const folderSearch = useFolderFilterStore((s) => s.keyword);
  const setFolderSearch = useFolderFilterStore((s) => s.setKeyword);
  const setFolderPath = useFolderFilterStore((s) => s.setFolderPath);
  const [dragOverFolderId, setDragOverFolderId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [favVersion, setFavVersion] = useState(0);
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

  const [clipboard, setClipboard] = useState<{ nodeIds: string[]; mode: 'copy' | 'cut' } | null>(null);
  const [focusedIndex, setFocusedIndex] = useState(-1);

  const { showToast } = useToast();
  const { confirm } = useConfirm();
  const { has } = usePermission();

  const fileListRef = useRef<HTMLDivElement>(null);
  const { dragRect, startDrag } = useDragSelect(fileListRef, setSelectedIds);

  const [showCreateFolder, setShowCreateFolder] = useState(false);
  const [renameTarget, setRenameTarget] = useState<FileNode | null>(null);
  const [moveTarget, setMoveTarget] = useState<{ nodeIds: string[]; mode: 'move' | 'copy' } | null>(null);

  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: FileNode } | null>(null);
  const [blankContextMenu, setBlankContextMenu] = useState<{ x: number; y: number } | null>(null);

  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);
  const [shareTarget, setShareTarget] = useState<FileNode | null>(null);
  const [downloadTarget, setDownloadTarget] = useState<FileNode | null>(null);
  const [versionTarget, setVersionTarget] = useState<FileNode | null>(null);

  const [isDragging, setIsDragging] = useState(false);
  const [lastSelectedId, setLastSelectedId] = useState<string | null>(null);

  const stateRef = useRef({ selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId });

  const [currentPath, setCurrentPath] = useState('/');
  const [pathEditMode, setPathEditMode] = useState(false);
  const [pathInput, setPathInput] = useState('/');
  const [pathError, setPathError] = useState(false);
  const pathInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    stateRef.current = { selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId };
  }, [selectedIds, files, clipboard, view, parentId, focusedIndex, lastSelectedId]);
  const { addFiles, addFilePaths, refreshSignal } = useUpload();

  const fetchFiles = useCallback(async () => {
    setLoading(true);
    try {
      const res = await source.listFiles(parentId, page, 50);
      setFiles(res?.records || []);
      setTotal(Number(res?.total || 0));
    } catch {
      setFiles([]);
    } finally {
      setLoading(false);
    }
  }, [source, parentId, page]);

  useEffect(() => {
    fetchFiles();
    setSelectedIds(new Set());
    setFocusedIndex(-1);
    setFolderSearch('');
    setDetailFile(null);
  }, [fetchFiles, refreshKey]);

  useEffect(() => {
    if (!parentId || parentId === '0' || parentId === 'null') {
      setCurrentPath('/');
      return;
    }
    let cancelled = false;
    source.getNodeById(parentId).then((node) => {
      if (!cancelled && node) {
        setCurrentPath(node.path || '/');
      }
    }).catch(() => {
      if (!cancelled) setCurrentPath('/');
    });
    return () => { cancelled = true; };
  }, [source, parentId]);

  useEffect(() => {
    if (refreshSignal > 0) setRefreshKey((k) => k + 1);
  }, [refreshSignal]);

  useEffect(() => {
    localStorage.setItem('fileView', view);
  }, [view]);

  const refresh = () => setRefreshKey((k) => k + 1);

  const handleDragOver = (e: React.DragEvent) => {
    if (e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    setIsDragging(true);
  };
  const handleDragLeave = (e: React.DragEvent) => {
    if (e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    if (e.currentTarget === e.target) setIsDragging(false);
  };
  const handleDrop = (e: React.DragEvent) => {
    if (e.dataTransfer.types.includes('application/x-file-ids')) return;
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
  };

  const handleItemDragStart = (e: React.DragEvent, node: FileNode) => {
    const ids = selectedIds.has(node.id) ? [...selectedIds] : [node.id];
    e.dataTransfer.setData('application/x-file-ids', JSON.stringify(ids));
    e.dataTransfer.effectAllowed = 'move';
  };

  const handleFolderDragOver = (e: React.DragEvent, folder: FileNode) => {
    if (!e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'move';
    setDragOverFolderId(folder.id);
  };

  const handleFolderDragLeave = (e: React.DragEvent, folder: FileNode) => {
    e.stopPropagation();
    setDragOverFolderId((prev) => (prev === folder.id ? null : prev));
  };

  const handleFolderDrop = async (e: React.DragEvent, folder: FileNode) => {
    const data = e.dataTransfer.getData('application/x-file-ids');
    if (!data) return;
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
  };

  const handleUploadClick = () => fileInputRef.current?.click();
  const handleUploadChange = (e: React.ChangeEvent<HTMLInputElement>) => {
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
  };

  const handleSelect = (id: string, e: React.MouseEvent) => {
    if (e.ctrlKey || e.metaKey) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      });
      setLastSelectedId(id);
    } else if (e.shiftKey && lastSelectedId) {
      const ids = files.map((f) => f.id);
      const start = ids.indexOf(lastSelectedId);
      const end = ids.indexOf(id);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        setSelectedIds(new Set(ids.slice(from, to + 1)));
      } else {
        setSelectedIds(new Set([id]));
      }
    } else {
      setSelectedIds(new Set([id]));
      setLastSelectedId(id);
    }
  };

  const selectAll = () => {
    setSelectedIds(new Set(files.map((f) => f.id)));
    setLastSelectedId(null);
  };
  const clearSelection = () => {
    setSelectedIds(new Set());
    setLastSelectedId(null);
  };

  const handleContextMenu = (e: React.MouseEvent, node: FileNode) => {
    e.preventDefault();
    if (!selectedIds.has(node.id)) {
      setSelectedIds(new Set([node.id]));
      setLastSelectedId(node.id);
    }
    setBlankContextMenu(null);
    setContextMenu({ x: e.clientX, y: e.clientY, node });
  };

  const handleItemMenu = (e: React.MouseEvent, node: FileNode) => {
    if (!selectedIds.has(node.id)) {
      setSelectedIds(new Set([node.id]));
      setLastSelectedId(node.id);
    }
    setBlankContextMenu(null);
    setContextMenu({ x: e.clientX, y: e.clientY, node });
  };

  const focusedId = focusedIndex >= 0 && focusedIndex < files.length ? files[focusedIndex].id : null;

  const moveFocus = useCallback((delta: number, extendSelection: boolean) => {
    const st = stateRef.current;
    if (st.files.length === 0) return;
    let newIndex = st.focusedIndex >= 0 ? st.focusedIndex + delta : 0;
    newIndex = Math.max(0, Math.min(st.files.length - 1, newIndex));
    setFocusedIndex(newIndex);
    const newId = st.files[newIndex].id;
    if (extendSelection) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        next.add(newId);
        return next;
      });
    } else {
      setSelectedIds(new Set([newId]));
      setLastSelectedId(newId);
    }
  }, []);

  const handlePasteRef = useRef<() => void>(() => {});
  handlePasteRef.current = async () => {
    const st = stateRef.current;
    if (!st.clipboard || st.clipboard.nodeIds.length === 0) return;
    try {
      if (st.clipboard.mode === 'copy') {
        await source.copy(st.clipboard.nodeIds, st.parentId || '0');
        showToast(`已粘贴 ${st.clipboard.nodeIds.length} 项`);
      } else {
        await source.move(st.clipboard.nodeIds, st.parentId || '0');
        showToast(`已移动 ${st.clipboard.nodeIds.length} 项`);
        setClipboard(null);
      }
      fetchFiles();
    } catch (err) {
      console.error('Paste failed:', err);
      showToast('粘贴失败', 'error');
    }
  };

  const handleDeleteRef = useRef<(nodeIds: string[]) => void>(() => {});
  handleDeleteRef.current = async (nodeIds: string[]) => {
    const confirmed = await confirm({
      title: '删除文件',
      message: `确定删除选中的 ${nodeIds.length} 个文件？文件将移入回收站。`,
      confirmText: '删除',
      danger: true,
    });
    if (!confirmed) return;
    try {
      await source.delete(nodeIds);
      showToast(`已删除 ${nodeIds.length} 项`);
      clearSelection();
      fetchFiles();
    } catch (err) {
      console.error('Delete failed:', err);
      showToast('删除失败', 'error');
    }
  };


  useFileKeyboard(
    () => stateRef.current,
    {
      setSelectedIds, setClipboard, setFocusedIndex, setLastSelectedId,
      setRenameTarget, setPreview, setContextMenu, setBlankContextMenu, setShowCreateFolder,
      selectAll, clearSelection, moveFocus, refresh, navigate,
      onBack, onNavigateFolder,
      handlePaste: () => handlePasteRef.current(),
      handleDelete: (ids: string[]) => handleDeleteRef.current(ids),
      showToast, hasPermission: has,
    },
  );

  const handleDownload = useCallback(async (nodeIds: string[]) => {
    try {
      if (isElectron() && nodeIds.length === 1) {
        const node = files.find((f) => f.id === nodeIds[0]);
        if (node && node.nodeType === 1) {
          setDownloadTarget(node);
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
        const blob = await source.downloadZip(nodeIds);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'download.zip';
        a.click();
        URL.revokeObjectURL(url);
      }
    } catch {
      showToast('下载失败', 'error');
    }
  }, [files, source, showToast]);

  const enterPathEditMode = () => {
    setPathInput(currentPath === '/' ? '/' : currentPath);
    setPathError(false);
    setPathEditMode(true);
    setTimeout(() => pathInputRef.current?.select(), 0);
  };

  const handlePathSubmit = async () => {
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
  };

  const navigateToPath = async (path: string) => {
    if (path === '/' || path === '') {
      onNavigateFolder({ id: '0', parentId: '0', nodeType: 0, name: '', path: '/', fileSize: null, suffix: null, contentType: null, status: 0, thumbnailPath: null, createdAt: '', updatedAt: '' });
      return;
    }
    try {
      const node = await source.resolveByPath(path);
      if (node && node.nodeType === 0) {
        onNavigateFolder(node);
      }
    } catch {
      showToast('路径不存在', 'error');
    }
  };

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

  const goUp = () => {
    if (pathSegments.length <= 1) {
      navigateToPath('/');
    } else {
      navigateToPath(pathSegments[pathSegments.length - 2].path);
    }
  };

  const handleContextAction = (action: string, node: FileNode) => {
    setContextMenu(null);
    switch (action) {
      case 'open':
        onNavigateFolder(node);
        break;
      case 'preview': {
        const fileFiles = files.filter((f) => f.nodeType === 1);
        const idx = fileFiles.findIndex((f) => f.id === node.id);
        if (idx >= 0) setPreview({ files: fileFiles, index: idx });
        break;
      }
      case 'cut':
        setClipboard({ nodeIds: [...selectedIds], mode: 'cut' });
        showToast(`\u5df2\u526a\u5207 ${selectedIds.size} \u9879`);
        break;
      case 'copy':
        setClipboard({ nodeIds: [...selectedIds], mode: 'copy' });
        showToast(`\u5df2\u590d\u5236 ${selectedIds.size} \u9879`);
        break;
      case 'paste':
        handlePasteRef.current();
        break;
      case 'rename':
        setRenameTarget(node);
        break;
      case 'download':
        handleDownload([node.id]);
        break;
      case 'moveTo':
        setMoveTarget({ nodeIds: [node.id], mode: 'move' });
        break;
      case 'copyTo':
        setMoveTarget({ nodeIds: [node.id], mode: 'copy' });
        break;
      case 'delete':
        handleDeleteRef.current([node.id]);
        break;
      case 'share':
        setShareTarget(node);
        break;
      case 'versions':
        setVersionTarget(node);
        break;
      case 'details':
        setDetailFile(node);
        break;
      case 'favorite': {
        const added = toggleFavorite(node);
        showToast(added ? '已收藏' : '已取消收藏');
        setFavVersion((v) => v + 1);
        break;
      }
    }
  };

  const sortedFiles = useMemo(() => {
    const arr = [...files];
    arr.sort((a, b) => {
      if (a.nodeType !== b.nodeType) return a.nodeType === 0 ? -1 : 1;
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
  }, [files, sortBy, sortDir]);

  const selectedSize = useMemo(
    () => files.reduce((sum, f) => selectedIds.has(f.id) ? sum + Number(f.fileSize || 0) : sum, 0),
    [files, selectedIds],
  );

  const [detailFile, setDetailFile] = useState<FileNode | null>(null);

  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const [folderSearchResults, setFolderSearchResults] = useState<FileNode[]>([]);

  useEffect(() => {
    setFolderPath(currentPath);
  }, [currentPath, setFolderPath]);

  useEffect(() => {
    if (!folderSearch.trim()) { setFolderSearchResults([]); return; }
    let cancelled = false;
    api.get<SearchResultVO[]>('/search', { params: { keyword: folderSearch, page: 1, size: 200 } })
      .then((res) => {
        if (cancelled) return;
        const prefix = currentPath === '/' ? '/' : currentPath + '/';
        const filtered = (res || []).filter((r) => {
          const p = r.path || '';
          return p === currentPath || p.startsWith(prefix) || (currentPath === '/' && p.startsWith('/'));
        });
        setFolderSearchResults(filtered.map((r) => ({
          id: r.fileId, parentId: '', nodeType: r.nodeType ?? 1, name: r.fileName.replace(/<[^>]*>/g, ''), path: r.path, fileSize: r.fileSize ?? '0', suffix: r.suffix, contentType: r.contentType, status: 0, thumbnailPath: null, createdAt: r.createdAt, updatedAt: r.updatedAt,
        })));
      })
      .catch(() => { if (!cancelled) setFolderSearchResults([]); });
    return () => { cancelled = true; };
  }, [folderSearch, currentPath]);

  const filteredFiles = useMemo(() => {
    if (!folderSearch.trim()) return sortedFiles;
    return folderSearchResults;
  }, [sortedFiles, folderSearch, folderSearchResults]);

  return (
    <div
      className="flex flex-col h-full bg-white"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleUploadChange} />
      {isDragging && (
        <div className="absolute inset-0 bg-primary-50/60 backdrop-blur-sm border-2 border-dashed border-primary-400 rounded-xl z-40 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <div className="w-16 h-16 bg-primary-100 rounded-2xl flex items-center justify-center mx-auto mb-3"><FolderInput className="w-8 h-8 text-primary-600" aria-hidden /></div>
            <p className="text-primary-700 font-medium">{'\u677e\u5f00\u9f20\u6807\u4e0a\u4f20\u6587\u4ef6'}</p>
          </div>
        </div>
      )}

      <div className="sticky top-0 z-20 flex items-center justify-between gap-2 px-5 py-2 bg-white/80 backdrop-blur-md border-b border-stone-100/80 overflow-x-auto">
        <div className="flex items-center gap-1.5 flex-shrink-0">
          {has('file:upload') && (
            <button onClick={() => setShowCreateFolder(true)} className="btn-primary flex-shrink-0 whitespace-nowrap">
              <FolderPlus className="w-4 h-4" aria-hidden />
              <span>{'\u65b0\u5efa\u6587\u4ef6\u5939'}</span>
            </button>
          )}
          {has('file:upload') && (
            <button onClick={handleUploadClick} className="btn-ghost flex-shrink-0 whitespace-nowrap">
              <Upload className="w-4 h-4" aria-hidden />
              <span>{'\u4e0a\u4f20\u6587\u4ef6'}</span>
            </button>
          )}
          {selectedIds.size > 0 && (
            <>
              <div className="w-px h-5 bg-stone-200 mx-1.5 flex-shrink-0" />
              {has('file:download') && (
                <button onClick={() => handleDownload([...selectedIds])} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                  <Download className="w-4 h-4" aria-hidden />
                  <span>{'\u4e0b\u8f7d'}</span>
                </button>
              )}
              {has('file:move') && (
                <button onClick={() => setMoveTarget({ nodeIds: [...selectedIds], mode: 'move' })} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                  <FolderInput className="w-4 h-4" aria-hidden />
                  <span>{'\u79fb\u52a8'}</span>
                </button>
              )}
              {has('file:copy') && (
                <button onClick={() => setMoveTarget({ nodeIds: [...selectedIds], mode: 'copy' })} className="btn-ghost flex-shrink-0 whitespace-nowrap">
                  <Copy className="w-4 h-4" aria-hidden />
                  <span>{'\u590d\u5236'}</span>
                </button>
              )}
              {has('file:delete') && (
                <button onClick={() => handleDeleteRef.current([...selectedIds])} className="btn-ghost text-red-600 hover:bg-red-50 flex-shrink-0 whitespace-nowrap">
                  <Trash2 className="w-4 h-4" aria-hidden />
                  <span>{'\u5220\u9664'}</span>
                </button>
              )}
            </>
          )}
        </div>

        <div className="flex items-center gap-3 flex-shrink-0">

          {selectedIds.size === 0 && (
          <div className="flex items-center gap-1.5">
            <ArrowDownUp className="w-3.5 h-3.5 text-stone-400" aria-hidden />
            <Select value={sortBy} onValueChange={(v) => { setSortBy(v as 'name' | 'size' | 'time'); localStorage.setItem('fileSortBy', v); }}>
              <SelectTrigger className="h-7 w-auto gap-1 text-xs border-stone-200 px-2.5 py-0.5 font-medium text-stone-600 hover:border-stone-300">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="min-w-[7rem]">
                <SelectItem value="name">名称</SelectItem>
                <SelectItem value="size">大小</SelectItem>
                <SelectItem value="time">修改时间</SelectItem>
              </SelectContent>
            </Select>
            <button
              onClick={() => {
                const next = sortDir === 'asc' ? 'desc' : 'asc';
                setSortDir(next);
                localStorage.setItem('fileSortDir', next);
              }}
              className="text-stone-400 hover:text-stone-600 cursor-pointer px-1 text-sm"
              title={sortDir === 'asc' ? '\u5347\u5e8f' : '\u964d\u5e8f'}
            >
              {sortDir === 'asc' ? '\u2191' : '\u2193'}
            </button>
          </div>
          )}
          {selectedIds.size > 0 && (
            <>
              {!allSelected && files.length > 1 && (
                <button onClick={selectAll} className="text-xs text-primary-600 hover:text-primary-700 cursor-pointer font-medium whitespace-nowrap">
                  {'\u5168\u9009'}
                </button>
              )}
              <div className="flex items-center gap-2 px-2.5 py-1 bg-primary-50 rounded-lg text-sm text-primary-700">
              <span className="font-medium">{'\u5df2\u9009'} {selectedIds.size} {'\u9879'}</span>
              {selectedSize > 0 && <span className="text-primary-400">{'\u00b7'} {formatSize(selectedSize)}</span>}
              <button onClick={clearSelection} aria-label="取消选择" className="text-primary-400 hover:text-primary-700 cursor-pointer ml-0.5">
                <X className="w-3.5 h-3.5" aria-hidden />
              </button>
            </div>
            </>
          )}
          <button onClick={refresh} aria-label="刷新" className="btn-ghost" title={'\u5237\u65b0'} >
            <RefreshCw className="w-4 h-4" aria-hidden />
          </button>
          {selectedIds.size === 0 && (
          <div className="flex items-center bg-stone-100 rounded-lg p-0.5">
            <button
              onClick={() => setView('list')} aria-label="列表视图"
              className={cn('p-1.5 rounded-md cursor-pointer transition', view === 'list' ? 'bg-white text-primary-600 shadow-sm' : 'text-stone-400 hover:text-stone-600')}
            >
              <List className="w-4 h-4" aria-hidden />
            </button>
            <button
              onClick={() => setView('grid')} aria-label="网格视图"
              className={cn('p-1.5 rounded-md cursor-pointer transition', view === 'grid' ? 'bg-white text-primary-600 shadow-sm' : 'text-stone-400 hover:text-stone-600')}
            >
              <LayoutGrid className="w-4 h-4" aria-hidden />
            </button>
          </div>
          )}
        </div>
      </div>

      {/* Address bar - Windows Explorer style */}
      <div className="flex items-center gap-2 px-4 py-2.5 border-b border-stone-200 bg-white">
        <button
          onClick={goUp} aria-label="返回上一级"
          disabled={currentPath === '/'}
          title={'返回上一级'}
          className="flex items-center justify-center w-8 h-8 rounded-md hover:bg-stone-100 text-stone-500 hover:text-primary-600 transition-colors flex-shrink-0 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent"
        >
          <ArrowUp className="w-4 h-4" aria-hidden />
        </button>
        {pathEditMode ? (
          <div className={cn('flex items-center gap-2 flex-1 bg-white border rounded-md px-3 py-1.5 ring-2 ring-primary-100', pathError ? 'border-red-400' : 'border-primary-400')}>
            <MapPin className={cn('w-4 h-4 flex-shrink-0', pathError ? 'text-red-500' : 'text-stone-400')} aria-hidden />
            <input
              ref={pathInputRef}
              value={pathInput}
              onChange={(e) => { setPathInput(e.target.value); setPathError(false); }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); handlePathSubmit(); }
                else if (e.key === 'Escape') { setPathEditMode(false); setPathError(false); }
              }}
              onBlur={() => { if (pathInput === currentPath) setPathEditMode(false); }}
              className={cn(
                'flex-1 text-sm bg-transparent px-1 py-1.5 outline-none transition-colors',
                pathError ? 'text-red-600' : 'text-stone-800',
              )}
              placeholder="/folder1/folder2"
              spellCheck={false}
            />
            <button
              onClick={handlePathSubmit}
              className="text-xs text-white bg-primary-600 rounded-md px-2 py-1 hover:bg-primary-700 cursor-pointer whitespace-nowrap"
            >
              {'转到'}
            </button>
            <button
              onClick={() => { setPathEditMode(false); setPathError(false); }} aria-label="取消"
              className="text-stone-400 hover:text-stone-600 cursor-pointer p-0.5"
            >
              <X className="w-4 h-4" aria-hidden />
            </button>
          </div>
        ) : (
          <div
            className="flex items-center gap-1 flex-1 min-w-0 overflow-hidden bg-white border border-stone-300 rounded-md px-3 py-2 hover:border-primary-400 hover:ring-2 hover:ring-primary-50 transition cursor-text group"
            role="button"
            tabIndex={0}
            aria-label="编辑路径"
            onClick={enterPathEditMode}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); enterPathEditMode(); } }}
            title={'点击编辑路径'}
          >
            <MapPin className="w-4 h-4 text-stone-400 flex-shrink-0" aria-hidden />
            <button
              onClick={(e) => { e.stopPropagation(); navigateToPath('/'); }} aria-label="根目录"
              className="flex items-center gap-1 px-2 py-1 rounded-lg bg-stone-50 hover:bg-primary-50 text-stone-500 hover:text-primary-600 transition-all duration-150 flex-shrink-0"
            >
              <Home className="w-4 h-4" aria-hidden />
            </button>
            {pathSegments.length === 0 ? (
              <span className="text-sm text-stone-400 px-1.5">{'根目录'}</span>
            ) : (
              pathSegments.map((seg, idx) => (
                <div key={seg.path} className="flex items-center gap-0.5 min-w-0">
                  <ChevronRight className="w-3 h-3 text-stone-300 flex-shrink-0" aria-hidden />
                  <button
                    onClick={(e) => { e.stopPropagation(); navigateToPath(seg.path); }}
                    className={cn(
                      'px-2 py-0.5 rounded-lg text-sm transition-all duration-150 truncate',
                      idx === pathSegments.length - 1
                        ? 'bg-primary-50 text-primary-700 font-medium'
                        : 'text-stone-500 hover:bg-stone-100 hover:text-primary-600',
                    )}
                  >
                    {seg.name}
                  </button>
                </div>
              ))
            )}
            <div className="flex-1" />
            <Pencil className="w-3.5 h-3.5 text-stone-300 group-hover:text-primary-500 transition-colors flex-shrink-0" aria-hidden />
          </div>
        )}
      </div>

      <div className="flex-1 flex overflow-hidden">
      <div
        ref={fileListRef}
        className="flex-1 overflow-auto px-5 py-4 relative"
        onMouseDown={(e) => {
          if (e.button === 0 && !isFileItemClick(e)) {
            e.preventDefault();
            startDrag(e.clientX, e.clientY);
            clearSelection();
            if (contextMenu) setContextMenu(null);
          }
        }}
        onContextMenu={(e) => {
          if (!isFileItemClick(e)) {
            e.preventDefault();
            clearSelection();
            setContextMenu(null);
            setBlankContextMenu({ x: e.clientX, y: e.clientY });
          }
        }}
      >
        {loading ? (
          <FileListSkeleton view={view} />
        ) : files.length === 0 ? (
          <EmptyState onCreateFolder={() => setShowCreateFolder(true)} />
        ) : view === 'list' ? (
          <FileTable
            files={filteredFiles}
            selectedIds={selectedIds}
            focusedId={focusedId}
            cutIds={clipboard?.mode === 'cut' ? new Set(clipboard.nodeIds) : null}
            onSelect={handleSelect}
            onSelectAll={selectAll}
            onContextMenu={handleContextMenu}
            onItemMenu={handleItemMenu}
            onNavigate={(node) => { if (node.nodeType === 0) onNavigateFolder(node); }}
            onItemDragStart={handleItemDragStart}
            onFolderDragOver={handleFolderDragOver}
            onFolderDragLeave={handleFolderDragLeave}
            onFolderDrop={handleFolderDrop}
            dragOverFolderId={dragOverFolderId}
            onDoubleClick={(node) => {
              if (node.nodeType === 0) {
                onNavigateFolder(node);
              } else if (has('file:preview')) {
                const fileFiles = files.filter((f) => f.nodeType === 1);
                const idx = fileFiles.findIndex((f) => f.id === node.id);
                setPreview({ files: fileFiles, index: idx >= 0 ? idx : 0 });
              }
            }}
          />
        ) : (
          <FileGrid
            files={filteredFiles}
            selectedIds={selectedIds}
            focusedId={focusedId}
            cutIds={clipboard?.mode === 'cut' ? new Set(clipboard.nodeIds) : null}
            onSelect={handleSelect}
            onContextMenu={handleContextMenu}
            onItemMenu={handleItemMenu}
            onNavigate={(node) => { if (node.nodeType === 0) onNavigateFolder(node); }}
            onItemDragStart={handleItemDragStart}
            onFolderDragOver={handleFolderDragOver}
            onFolderDragLeave={handleFolderDragLeave}
            onFolderDrop={handleFolderDrop}
            dragOverFolderId={dragOverFolderId}
            onDoubleClick={(node) => {
              if (node.nodeType === 0) {
                onNavigateFolder(node);
              } else if (has('file:preview')) {
                const fileFiles = files.filter((f) => f.nodeType === 1);
                const idx = fileFiles.findIndex((f) => f.id === node.id);
                setPreview({ files: fileFiles, index: idx >= 0 ? idx : 0 });
              }
            }}
          />
        )}
      </div>
      {detailFile && (
        <FileDetailPanel file={detailFile} onClose={() => setDetailFile(null)} />
      )}
      </div>

      {total > 50 && (
        <div className="flex items-center justify-between px-4 py-3 border-t border-stone-200 bg-white">
          <span className="text-sm text-stone-500">{'\u5171'} {total} {'\u9879'}</span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page <= 1}
              className="btn-ghost disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {'\u4e0a\u4e00\u9875'}
            </button>
            <span className="text-sm text-stone-700">{page}</span>
            <button
              onClick={() => setPage((p) => p + 1)}
              disabled={page * 50 >= total}
              className="btn-ghost disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {'\u4e0b\u4e00\u9875'}
            </button>
          </div>
        </div>
      )}

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          node={contextMenu.node}
          hasClipboard={!!clipboard}
          showShare={enableShare}
          showVersions={enableVersions}
          isFav={favVersion >= 0 && isFavorite(contextMenu.node.id)}
          onAction={handleContextAction}
          onClose={() => setContextMenu(null)}
        />
      )}

      {blankContextMenu && (
        <BlankContextMenu
          x={blankContextMenu.x}
          y={blankContextMenu.y}
          hasClipboard={!!clipboard}
          onAction={(action) => {
            switch (action) {
              case 'paste': handlePasteRef.current(); break;
              case 'newFolder': setShowCreateFolder(true); break;
              case 'upload': handleUploadClick(); break;
              case 'refresh': fetchFiles(); break;
              case 'selectAll': selectAll(); break;
            }
          }}
          onClose={() => setBlankContextMenu(null)}
        />
      )}

      <CreateFolderDialog
        open={showCreateFolder}
        parentId={parentId || '0'}
        onCreate={(pid, name) => source.createFolder(pid, name)}
        onClose={() => setShowCreateFolder(false)}
        onSuccess={() => { setShowCreateFolder(false); fetchFiles(); }}
      />
      <RenameDialog
        node={renameTarget}
        onRename={(id, name) => source.rename(id, name)}
        onClose={() => setRenameTarget(null)}
        onSuccess={() => { setRenameTarget(null); fetchFiles(); }}
      />
      {moveTarget && (
        <MoveDialog
          nodeIds={moveTarget.nodeIds}
          mode={moveTarget.mode}
          loadTree={() => source.loadTree()}
          onConfirm={(ids, tid, mode) => mode === 'move' ? source.move(ids, tid) : source.copy(ids, tid)}
          onClose={() => setMoveTarget(null)}
          onSuccess={() => { setMoveTarget(null); fetchFiles(); clearSelection(); }}
        />
      )}

      {preview && (
        <PreviewModal
          files={preview.files}
          currentIndex={preview.index}
          onClose={() => setPreview(null)}
        />
      )}

      {enableShare && shareTarget && (
        <ShareDialog
          fileNodeId={shareTarget.id}
          fileName={shareTarget.name}
          onClose={() => setShareTarget(null)}
        />
      )}

      {enableVersions && versionTarget && (
        <VersionHistoryDialog
          node={versionTarget}
          onClose={() => setVersionTarget(null)}
          onRestored={() => fetchFiles()}
        />
      )}

      {downloadTarget && (
        <DownloadDialog
          fileName={downloadTarget.name}
          fileSize={downloadTarget.fileSize ? parseInt(downloadTarget.fileSize) : 0}
          onClose={() => setDownloadTarget(null)}
          onConfirm={async (savePath) => {
            try {
              await window.electronAPI!.startDownload(
                downloadTarget.id,
                downloadTarget.name,
                downloadTarget.fileSize ? parseInt(downloadTarget.fileSize) : 0,
                savePath
              );
              showToast('\u5df2\u6dfb\u52a0\u5230\u4e0b\u8f7d\u961f\u5217', 'success');
              return true;
            } catch {
              showToast('\u4e0b\u8f7d\u542f\u52a8\u5931\u8d25', 'error');
              return false;
            }
          }}
        />
      )}

      {dragRect && (
        <div
          className="fixed border border-primary-400 bg-primary-500/10 pointer-events-none z-30 rounded-sm"
          style={{
            left: Math.min(dragRect.startX, dragRect.currentX),
            top: Math.min(dragRect.startY, dragRect.currentY),
            width: Math.abs(dragRect.currentX - dragRect.startX),
            height: Math.abs(dragRect.currentY - dragRect.startY),
          }}
        />
      )}
    </div>
  );
}
