import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { isElectron } from '../../lib/electron';
import api from '../../lib/api';
import { useTransferStore } from '../../store/transfer';
import type { BlankFileType, FileNode } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import FileToolbar from './FileToolbar';
import FileBreadcrumb from './FileBreadcrumb';
import FileList from './FileList';
import ContextMenu from './ContextMenu';
import { useMobile } from '../../hooks/useMobile';
import { usePullToRefresh } from '../../hooks/usePullToRefresh';
import { isCapacitor } from '../../lib/runtime';
import { pickFromGallery } from '../../lib/capacitor';
import MultiSelectBar from '../ui/MultiSelectBar';
import { RefreshCw } from 'lucide-react';
import BlankContextMenu from './BlankContextMenu';
import MoveDialog from './MoveDialog';
import DownloadDialog from './DownloadDialog';
import { CreateFolderDialog, CreateFileDialog, RenameDialog, EmptyState, FileListSkeleton } from './Dialogs';
import FileDetailPanel from './FileDetailPanel';
import PreviewModal from '../preview/PreviewModal';
import ShareDialog from '../share/ShareDialog';
import VersionHistoryDialog from './VersionHistoryDialog';
import BatchRenameDialog from './BatchRenameDialog';
import ArchiveDialog from './ArchiveDialog';
import ConvertDialog from './ConvertDialog';
import { useToast } from '../ui/Toast';
import { useConfirm } from '../ui/ConfirmDialog';
import { useUpload } from '../../hooks/useUpload';
import { usePermission } from '../../lib/permission';
import { useDragSelect } from '../../hooks/useDragSelect';
import { useFileKeyboard } from '../../hooks/useFileKeyboard';
import { useFolderFilterStore } from '../../store/folderFilter';
import { useFavoritesStore } from '../../store/favorites';
import { FolderInput } from 'lucide-react';
import { isEditableOfficeSuffix } from '../../lib/editor';
import { useFileSelection } from '../../hooks/useFileSelection';
import { useFileClipboard } from '../../hooks/useFileClipboard';
import { useFileDialogs } from '../../hooks/useFileDialogs';
import { useFolderSearch } from '../../hooks/useFolderSearch';

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
  /** 页面级详情回调：由页面统一管理详情视图（TeamSpacePage）；未提供时回退到内部右侧边栏（兼容其它页面） */
  onOpenDetail?: (node: FileNode) => void;
  /** 团队空间右键锁定/解锁回调（仅团队空间传入；执行 POST /team/{spaceId}/files/{nodeId}/lock|unlock） */
  onToggleLock?: (action: 'lock' | 'unlock', node: FileNode) => void;
}

/** 节点是否已锁定：以后端锁定字段为准（lockedBy 非空且未过期即视为锁定） */
function isNodeLocked(node: FileNode): boolean {
  if (node.lockedBy == null) return false;
  return node.lockExpireAt == null || new Date(node.lockExpireAt).getTime() > Date.now();
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
  categoryLabel,
  focusId,
  onOpenDetail,
  onToggleLock,
}: FileBrowserProps) {

  /** Returns true if the click landed on a file/folder item (not blank area) */
  const isFileItemClick = (e: React.MouseEvent): boolean => {
    const el = e.target as HTMLElement;
    return !!(el?.closest && el.closest('[data-file-id]'));
  };


  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [files, setFiles] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<'table' | 'card' | 'grid'>(() => {
    const saved = localStorage.getItem('fileView');
    if (saved === 'grid' || saved === 'card') return saved;
    return 'table';
  });
  const folderSearch = useFolderFilterStore((s) => s.keyword);
  const setFolderSearch = useFolderFilterStore((s) => s.setKeyword);
  const setFolderPath = useFolderFilterStore((s) => s.setFolderPath);
  const [dragOverFolderId, setDragOverFolderId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const { toggleFavorite: toggleFav, isFavorite: checkFav } = useFavoritesStore();
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
  // 文件夹优先开关（默认开启，对齐 PikPak）
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
  const { has } = usePermission();
  const isMobile = useMobile();

  /** 是否可在线编辑：docx/xlsx/pptx 且当前用户具备编辑（上传）权限；最终权限以后端 config 接口为准 */
  const canEditNode = (node: FileNode) =>
    node.nodeType === 1 && isEditableOfficeSuffix(node.suffix) && has('file:upload');

  // 选择状态与移动端多选模式
  const selection = useFileSelection(files, isMobile);
  const {
    selectedIds, setSelectedIds, setFocusedIndex, setLastSelectedId,
    focusedIndex, lastSelectedId, mobileSelectMode, setMobileSelectMode,
    handleSelect, toggleSelect, selectAll, clearSelection, moveFocus,
    handleMobileLongPress, handleMobileClick,
  } = selection;

  // 剪贴板（复制/剪切/粘贴）
  const { clipboard, setClipboard, paste } = useFileClipboard(source, parentId, showToast, () => fetchFiles());

  // 各类对话框/浮层目标状态
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

  // 从编辑器「以预览打开」回退：接收路由 state.openPreview，在目录加载完成后自动打开预览
  const pendingPreviewIdRef = useRef<string | null>(null);
  useEffect(() => {
    const st = (location.state ?? null) as { openPreview?: string } | null;
    if (st?.openPreview) {
      pendingPreviewIdRef.current = st.openPreview;
      // 消费一次后清除路由状态，避免刷新/重复进入再次弹预览
      navigate(location.pathname + location.search, { replace: true, state: null });
    }
    // 仅在挂载时读取一次（来源导航 state），不随路由变化重复触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [isDragging, setIsDragging] = useState(false);
  const prevParentId = useRef<string | null>(null);

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

  // 当前文件夹内搜索（关键词来自全局 folderFilter store）
  const folderSearchResults = useFolderSearch(folderSearch, currentPath);

  // 是否已有列表数据：用于决定是否显示骨架屏，避免切换文件夹时内容跳变
  const hasFilesRef = useRef(false);
  useEffect(() => {
    hasFilesRef.current = files.length > 0;
  }, [files]);

  const fetchFiles = useCallback(async () => {
    if (!hasFilesRef.current) setLoading(true);
    try {
      const res = await source.listFiles(parentId, page, 50);
      const records = res?.records || [];
      setFiles(records);
      setTotal(Number(res?.total || 0));
      // 编辑器「以预览打开」回退：当前目录存在该文件时自动打开预览
      const pendingId = pendingPreviewIdRef.current;
      if (pendingId) {
        pendingPreviewIdRef.current = null;
        const fileFiles = records.filter((f) => f.nodeType === 1);
        const idx = fileFiles.findIndex((f) => f.id === pendingId);
        if (idx >= 0) setPreview({ files: fileFiles, index: idx });
      }
    } catch {
      setFiles([]);
    } finally {
      setLoading(false);
    }
  }, [source, parentId, page, setPreview]);

  useEffect(() => {
    // 换文件夹：保留旧列表不清空，新数据到达后平滑替换，避免抖动
    prevParentId.current = parentId;
    
    fetchFiles();
    clearSelection();
    setFocusedIndex(-1);
    setFolderSearch('');
    setDetailFile(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchFiles, refreshKey]);

  // External focus request: preselect + scroll to a file (e.g. from homepage "jump to folder")
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

  // 移动端:选中清空时自动退出多选模式
  useEffect(() => {
    if (isMobile && mobileSelectMode && selectedIds.size === 0) {
      setMobileSelectMode(false);
    }
  }, [isMobile, mobileSelectMode, selectedIds.size, setMobileSelectMode]);

  // 移动端下拉刷新
  const ptr = usePullToRefresh({ onRefresh: async () => { await fetchFiles(); } });

  const refresh = () => setRefreshKey((k) => k + 1);

  const handleSortChange = (col: 'name' | 'size' | 'time') => {
    if (col === sortBy) {
      const next = sortDir === 'asc' ? 'desc' : 'asc';
      setSortDir(next);
      localStorage.setItem('fileSortDir', next);
    } else {
      setSortBy(col);
      localStorage.setItem('fileSortBy', col);
    }
  };

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

  const handleUploadClick = async () => {
    // Capacitor 壳: 调用原生相册选择,权限拒绝时 toast 引导
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
    // Web/Electron: 降级 input file
    fileInputRef.current?.click();
  };
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

  /**
   * 新建空白文件（txt/docx/xlsx/pptx）：
   * - 调接口成功 → 刷新列表；Office 类型（docx/xlsx/pptx）自动跳转在线编辑并携带来源路径（P3），txt 留在列表
   * - 失败（配额/权限等）→ toast 提示后端错误信息，不跳转编辑
   */
  const handleNewFile = async (type: BlankFileType) => {
    // 弹出文件名输入框（预填默认名），用户确认后创建
    setNewFileType(type);
  };

  const handleCreateFile = async (type: BlankFileType, fileName: string) => {
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
  };

  const handleContextMenu = (e: React.MouseEvent, node: FileNode) => {
    e.preventDefault();
    // 移动端: onContextMenu 由长按触发,进入多选模式而非弹菜单
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
  };

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
      handlePaste: paste,
      handleDelete: (ids: string[]) => handleDeleteRef.current(ids),
      showToast, hasPermission: has,
    },
    !isMobile,
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
  }, [files, source, showToast, setDownloadTarget]);

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

  /** 打开在线编辑器：记录来源路径，关闭/回退后返回当前目录（FileBrowser 重挂载即刷新列表） */
  const handleEdit = (node: FileNode) => {
    navigate(`/file/${node.id}/editor`, { state: { from: location.pathname + location.search } });
  };

  const handleContextAction = async (action: string, node: FileNode) => {
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
      case 'edit':
        handleEdit(node);
        break;
      case 'textEdit':
        navigate(`/file/${node.id}/text-editor`, {
          state: { from: location.pathname + location.search, name: node.name, spaceId: uploadSpaceId ?? undefined },
        });
        break;
      case 'convert':
        setConvertTarget(node);
        break;
      case 'cut':
        setClipboard({ nodeIds: [...selectedIds], mode: 'cut' });
        showToast(`已剪切 ${selectedIds.size} 项`);
        break;
      case 'copy':
        setClipboard({ nodeIds: [...selectedIds], mode: 'copy' });
        showToast(`已复制 ${selectedIds.size} 项`);
        break;
      case 'paste':
        paste();
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
        // 详情由页面统一管理（onOpenDetail）；未提供时回退到内部边栏，保持其它页面行为不变
        if (onOpenDetail) onOpenDetail(node);
        else setDetailFile(node);
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
        // 锁定/解锁成功后刷新列表：锁定状态以后端字段为准，刷新后他人锁定/解锁可见
        await onToggleLock?.(action as 'lock' | 'unlock', node);
        fetchFiles();
        break;
    }
  };

  /** 切换单个文件/文件夹的收藏状态 */
  const handleToggleFavorite = async (node: FileNode) => {
    const added = await toggleFav(node);
    showToast(added ? '已收藏' : '已取消收藏');
  };

  const sortedFiles = useMemo(() => {
    const arr = [...files];
    arr.sort((a, b) => {
      // 文件夹优先于文件（受 foldersFirst 开关控制）
      if (foldersFirst && a.nodeType !== b.nodeType) return a.nodeType === 0 ? -1 : 1;
      // 收藏项在同类型内置顶
      const aFav = checkFav(a.id) ? 1 : 0;
      const bFav = checkFav(b.id) ? 1 : 0;
      if (aFav !== bFav) return bFav - aFav;
      // 按用户选择的排序字段排列
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

  const selectedSize = useMemo(
    () => files.reduce((sum, f) => selectedIds.has(f.id) ? sum + Number(f.fileSize || 0) : sum, 0),
    [files, selectedIds],
  );

  const [detailFile, setDetailFile] = useState<FileNode | null>(null);

  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));

  // 工具栏「在线编辑」：仅当单选一个可编辑 Office 文件（docx/xlsx/pptx + 编辑权限）时显示
  const editableSelected = (() => {
    if (selectedIds.size !== 1) return null;
    const node = files.find((f) => selectedIds.has(f.id));
    return node && canEditNode(node) ? node : null;
  })();

  useEffect(() => {
    setFolderPath(currentPath);
  }, [currentPath, setFolderPath]);

  const filteredFiles = useMemo(() => {
    if (!folderSearch.trim()) return sortedFiles;
    return folderSearchResults;
  }, [sortedFiles, folderSearch, folderSearchResults]);

  // 已锁定节点 ID 集合：由列表节点上的后端锁定字段推导，供列表视图渲染锁图标与右键菜单判断
  const lockedIds = useMemo(() => {
    const set = new Set<string>();
    for (const f of files) {
      if (isNodeLocked(f)) set.add(f.id);
    }
    return set;
  }, [files]);

  return (
    <div
      className="flex flex-col h-full bg-surface"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleUploadChange} />
      {isDragging && (
        <div className="absolute inset-0 bg-primary-500/10 backdrop-blur-sm border-2 border-dashed border-primary-400 rounded-xl z-40 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <div className="w-16 h-16 bg-primary-500/20 rounded-2xl flex items-center justify-center mx-auto mb-3"><FolderInput className="w-8 h-8 text-primary-600" aria-hidden /></div>
            <p className="text-primary-600 font-medium">{'\u677e\u5f00\u9f20\u6807\u4e0a\u4f20\u6587\u4ef6'}</p>
          </div>
        </div>
      )}

      <FileToolbar
        has={has}
        selectedCount={selectedIds.size}
        filesCount={files.length}
        allSelected={allSelected}
        selectedSize={selectedSize}
        canEditSelected={!!editableSelected}
        onEdit={() => editableSelected && handleEdit(editableSelected)}
        sortBy={sortBy}
        onSortChange={(v) => { setSortBy(v); localStorage.setItem('fileSortBy', v); }}
        sortDir={sortDir}
        onSortDirToggle={() => { const next = sortDir === 'asc' ? 'desc' : 'asc'; setSortDir(next); localStorage.setItem('fileSortDir', next); }}
        view={view}
        onViewChange={setView}
        onNewFolder={() => setShowCreateFolder(true)}
        onNewFile={handleNewFile}
        onUploadClick={handleUploadClick}
        onDownload={() => handleDownload([...selectedIds])}
        onMove={() => setMoveTarget({ nodeIds: [...selectedIds], mode: 'move' })}
        onCopy={() => setMoveTarget({ nodeIds: [...selectedIds], mode: 'copy' })}
        onDelete={() => handleDeleteRef.current([...selectedIds])}
        onSelectAll={selectAll}
        onClearSelection={clearSelection}
        onRefresh={refresh}
        onBatchRename={() => setShowBatchRename(true)}
        foldersFirst={foldersFirst}
        onToggleFoldersFirst={(v) => { setFoldersFirst(v); localStorage.setItem('fileFoldersFirst', String(v)); }}
      />
      {isMobile && mobileSelectMode && (
        <MultiSelectBar
          selectedCount={selectedIds.size}
          allSelected={allSelected}
          onSelectAll={selectAll}
          onDownload={() => handleDownload([...selectedIds])}
          onDelete={() => handleDeleteRef.current([...selectedIds])}
          onShare={() => { if (selectedIds.size === 1) { const n = files.find((f) => selectedIds.has(f.id)); if (n) setShareTarget(n); } }}
          onCancel={() => { setMobileSelectMode(false); clearSelection(); }}
          canDownload={has('file:download')}
          canDelete={has('file:delete')}
          canShare={enableShare && has('file:share')}
        />
      )}
      {categoryLabel ? (
        <div className="flex items-center gap-2 px-4 py-2 border-b border-border bg-surface">
          <span className="text-sm font-medium text-fg">{categoryLabel}</span>
          <span className="text-xs text-muted">{total} 项</span>
        </div>
      ) : (
      <FileBreadcrumb
        currentPath={currentPath}
        pathEditMode={pathEditMode}
        setPathEditMode={setPathEditMode}
        pathInput={pathInput}
        setPathInput={setPathInput}
        pathError={pathError}
        setPathError={setPathError}
        onPathSubmit={handlePathSubmit}
        onEnterEditMode={enterPathEditMode}
        onGoUp={goUp}
        pathSegments={pathSegments}
        onNavigateToPath={navigateToPath}
        pathInputRef={pathInputRef}
      />
      )}

      <div className="flex-1 min-h-0 flex overflow-hidden">
      <div
        ref={fileListRef}
        className="flex-1 min-h-0 min-w-0 overflow-auto px-5 py-4 relative"
        onTouchStart={isMobile ? ptr.onTouchStart : undefined}
        onTouchMove={isMobile ? ptr.onTouchMove : undefined}
        onTouchEnd={isMobile ? ptr.onTouchEnd : undefined}
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
          {/* 移动端下拉刷新指示器 */}
          {isMobile && (ptr.pullDistance > 0 || ptr.refreshing) && (
            <div
              className="flex items-center justify-center text-muted"
              style={{ height: ptr.pullDistance, opacity: ptr.pullDistance > 10 ? 1 : 0 }}
            >
              <RefreshCw
                className={"w-5 h-5 " + (ptr.refreshing || ptr.pullDistance >= 70 ? "animate-spin" : "")}
                aria-hidden
              />
            </div>
          )}
          {loading ? (
            <FileListSkeleton view={view} />
        ) : files.length === 0 ? (
          <EmptyState onCreateFolder={() => setShowCreateFolder(true)} />
        ) : (
          <FileList
            view={view}
            files={filteredFiles}
            lockedIds={lockedIds}
            selectedIds={selectedIds}
            focusedId={focusedId}
            cutIds={clipboard?.mode === 'cut' ? new Set(clipboard.nodeIds) : null}
            sortBy={sortBy}
            sortDir={sortDir}
            onSortChange={handleSortChange}
            onSelect={handleSelect}
            onToggleSelect={toggleSelect}
            onSelectAll={selectAll}
            isFavorite={checkFav}
            onToggleFavorite={handleToggleFavorite}
            onContextMenu={handleContextMenu}
            onNavigate={(node) => { if (isMobile && mobileSelectMode) { handleMobileClick(node); return; } if (node.nodeType === 0) onNavigateFolder(node); }}
            onDoubleClick={(node) => {
              if (node.nodeType === 0) {
                onNavigateFolder(node);
              } else if (has('file:preview')) {
                const fileFiles = files.filter((f) => f.nodeType === 1);
                const idx = fileFiles.findIndex((f) => f.id === node.id);
                setPreview({ files: fileFiles, index: idx >= 0 ? idx : 0 });
              }
            }}
            onItemDragStart={handleItemDragStart}
            onFolderDragOver={handleFolderDragOver}
            onFolderDragLeave={handleFolderDragLeave}
            onFolderDrop={handleFolderDrop}
            dragOverFolderId={dragOverFolderId}
          />
        )}
      </div>
      {!onOpenDetail && detailFile && (
        <FileDetailPanel file={detailFile} onClose={() => setDetailFile(null)} />
      )}
      </div>

      {total > 50 && (
        <div className="flex items-center justify-between px-4 py-3 border-t border-border bg-surface">
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
          isFav={checkFav(contextMenu.node.id)}
          lockable={!!onToggleLock}
          locked={isNodeLocked(contextMenu.node)}
          showEdit={canEditNode(contextMenu.node)}
          showConvert
          showTextEdit
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
              case 'paste': paste(); break;
              case 'newFolder': setShowCreateFolder(true); break;
              case 'upload': handleUploadClick(); break;
              case 'refresh': fetchFiles(); break;
              case 'selectAll': selectAll(); break;
            }
          }}
          onNewFile={handleNewFile}
          onClose={() => setBlankContextMenu(null)}
        />
      )}

      {archiveTarget && (
        <ArchiveDialog
          file={archiveTarget}
          onClose={() => setArchiveTarget(null)}
          onExtracted={() => fetchFiles()}
        />
      )}
      {showBatchRename && (
        <BatchRenameDialog
          files={filteredFiles.filter((f) => selectedIds.has(f.id))}
          onClose={() => setShowBatchRename(false)}
          onSuccess={() => { fetchFiles(); }}
        />
      )}
      <CreateFolderDialog
        open={showCreateFolder}
        parentId={parentId || '0'}
        onCreate={(pid, name) => source.createFolder(pid, name)}
        onClose={() => setShowCreateFolder(false)}
        onSuccess={() => { setShowCreateFolder(false); fetchFiles(); }}
      />
      <CreateFileDialog
        open={newFileType !== null}
        type={newFileType}
        onCreate={handleCreateFile}
        onClose={() => setNewFileType(null)}
      />
      <RenameDialog
        node={renameTarget}
        onRename={(id, name) => source.rename(id, name)}
        onClose={() => setRenameTarget(null)}
        onSuccess={() => { setRenameTarget(null); fetchFiles(); }}
      />
      <ConvertDialog
        node={convertTarget}
        onClose={() => setConvertTarget(null)}
        onConverted={fetchFiles}
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
