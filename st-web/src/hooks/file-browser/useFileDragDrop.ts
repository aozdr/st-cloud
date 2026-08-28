import { useCallback, type Dispatch, type MutableRefObject, type SetStateAction } from 'react';
import { isElectron } from '../../lib/electron';
import type { FileNode } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import type { useToast } from '../../components/ui/Toast';

type ShowToast = ReturnType<typeof useToast>['showToast'];

/**
 * 文件浏览拖拽模块：外部文件拖入上传、条目内部拖拽移动到文件夹。
 * 拆分自 useFileBrowser.ts，行为保持不变。
 */
export function useFileDragDrop({
  parentId, uploadSpaceId, selectedIds, source, showToast, fetchFiles,
  addFiles, addFilePaths, isInternalDragRef, setIsDragging, setDragOverFolderId,
}: {
  parentId: string | null;
  uploadSpaceId?: string;
  selectedIds: Set<string>;
  source: FileSource;
  showToast: ShowToast;
  fetchFiles: () => void | Promise<void>;
  addFiles: (files: File[], parentId: string, replaceFileId?: string, spaceId?: string) => void;
  addFilePaths: (paths: string[], parentId: string, replaceFileId?: string, spaceId?: string) => void;
  isInternalDragRef: MutableRefObject<boolean>;
  setIsDragging: Dispatch<SetStateAction<boolean>>;
  setDragOverFolderId: Dispatch<SetStateAction<string | null>>;
}) {
  const handleDragOver = useCallback((e: React.DragEvent) => {
    if (isInternalDragRef.current || e.dataTransfer.types.includes('application/x-file-ids')) return;
    const isFileDrag = e.dataTransfer.types.includes('Files') || e.dataTransfer.files.length > 0;
    if (!isFileDrag) return;
    e.preventDefault();
    setIsDragging(true);
  }, [isInternalDragRef, setIsDragging]);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    if (isInternalDragRef.current || e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    if (e.currentTarget === e.target) setIsDragging(false);
  }, [isInternalDragRef, setIsDragging]);

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
  }, [addFilePaths, addFiles, parentId, uploadSpaceId, setIsDragging, isInternalDragRef]);

  const handleItemDragStart = useCallback((e: React.DragEvent, node: FileNode) => {
    isInternalDragRef.current = true;
    const ids = selectedIds.has(node.id) ? [...selectedIds] : [node.id];
    e.dataTransfer.setData('application/x-file-ids', JSON.stringify(ids));
    e.dataTransfer.effectAllowed = 'move';
  }, [selectedIds, isInternalDragRef]);

  const handleFolderDragOver = useCallback((e: React.DragEvent, folder: FileNode) => {
    if (!isInternalDragRef.current && !e.dataTransfer.types.includes('application/x-file-ids')) return;
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'move';
    setDragOverFolderId(folder.id);
  }, [setDragOverFolderId, isInternalDragRef]);

  const handleFolderDragLeave = useCallback((e: React.DragEvent, folder: FileNode) => {
    e.stopPropagation();
    setDragOverFolderId((prev: string | null) => (prev === folder.id ? null : prev));
  }, [setDragOverFolderId]);

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
      showToast('移动成功', 'success');
      fetchFiles();
    } catch {
      showToast('移动失败', 'error');
    }
  }, [source, showToast, fetchFiles, setDragOverFolderId, isInternalDragRef]);

  return { handleDragOver, handleDragLeave, handleDrop, handleItemDragStart, handleFolderDragOver, handleFolderDragLeave, handleFolderDrop };
}
