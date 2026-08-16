import { useState } from 'react';
import type { BlankFileType, FileNode } from '../types';

export interface FileDialogs {
  showCreateFolder: boolean;
  setShowCreateFolder: React.Dispatch<React.SetStateAction<boolean>>;
  newFileType: BlankFileType | null;
  setNewFileType: React.Dispatch<React.SetStateAction<BlankFileType | null>>;
  showBatchRename: boolean;
  setShowBatchRename: React.Dispatch<React.SetStateAction<boolean>>;
  archiveTarget: FileNode | null;
  setArchiveTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  renameTarget: FileNode | null;
  setRenameTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  convertTarget: FileNode | null;
  setConvertTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  moveTarget: { nodeIds: string[]; mode: 'move' | 'copy' } | null;
  setMoveTarget: React.Dispatch<React.SetStateAction<{ nodeIds: string[]; mode: 'move' | 'copy' } | null>>;
  shareTarget: FileNode | null;
  setShareTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  downloadTarget: FileNode | null;
  setDownloadTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  versionTarget: FileNode | null;
  setVersionTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  preview: { files: FileNode[]; index: number } | null;
  setPreview: React.Dispatch<React.SetStateAction<{ files: FileNode[]; index: number } | null>>;
  contextMenu: { x: number; y: number; node: FileNode } | null;
  setContextMenu: React.Dispatch<React.SetStateAction<{ x: number; y: number; node: FileNode } | null>>;
  blankContextMenu: { x: number; y: number } | null;
  setBlankContextMenu: React.Dispatch<React.SetStateAction<{ x: number; y: number } | null>>;
}

/** 文件浏览器各类对话框/浮层目标状态（新建/重命名/移动/分享/预览/右键菜单等） */
export function useFileDialogs(): FileDialogs {
  const [showCreateFolder, setShowCreateFolder] = useState(false);
  const [newFileType, setNewFileType] = useState<BlankFileType | null>(null);
  const [showBatchRename, setShowBatchRename] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<FileNode | null>(null);
  const [renameTarget, setRenameTarget] = useState<FileNode | null>(null);
  const [convertTarget, setConvertTarget] = useState<FileNode | null>(null);
  const [moveTarget, setMoveTarget] = useState<{ nodeIds: string[]; mode: 'move' | 'copy' } | null>(null);
  const [shareTarget, setShareTarget] = useState<FileNode | null>(null);
  const [downloadTarget, setDownloadTarget] = useState<FileNode | null>(null);
  const [versionTarget, setVersionTarget] = useState<FileNode | null>(null);
  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: FileNode } | null>(null);
  const [blankContextMenu, setBlankContextMenu] = useState<{ x: number; y: number } | null>(null);

  return {
    showCreateFolder,
    setShowCreateFolder,
    newFileType,
    setNewFileType,
    showBatchRename,
    setShowBatchRename,
    archiveTarget,
    setArchiveTarget,
    renameTarget,
    setRenameTarget,
    convertTarget,
    setConvertTarget,
    moveTarget,
    setMoveTarget,
    shareTarget,
    setShareTarget,
    downloadTarget,
    setDownloadTarget,
    versionTarget,
    setVersionTarget,
    preview,
    setPreview,
    contextMenu,
    setContextMenu,
    blankContextMenu,
    setBlankContextMenu,
  };
}
