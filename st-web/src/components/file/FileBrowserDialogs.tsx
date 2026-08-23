import type { Dispatch, SetStateAction } from 'react';
import { useNavigate } from 'react-router-dom';
import type { BlankFileType, FileNode } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import { isEditableOfficeSuffix } from '../../lib/editor';
import { formatSize } from '../../lib/utils';
import { CheckCircle2, ListChecks, Loader2 } from 'lucide-react';
import ContextMenu from './ContextMenu';
import BlankContextMenu from './BlankContextMenu';
import ArchiveDialog from './ArchiveDialog';
import BatchRenameDialog from './BatchRenameDialog';
import { CreateFolderDialog, CreateFileDialog, RenameDialog } from './Dialogs';
import ConvertDialog from './ConvertDialog';
import MoveDialog from './MoveDialog';
import PreviewModal from '../preview/PreviewModal';
import ShareDialog from '../share/ShareDialog';
import VersionHistoryDialog from './VersionHistoryDialog';
import DownloadDialog from './DownloadDialog';

/** 节点是否已锁定：以后端锁定字段为准（lockedBy 非空且未过期即视为锁定） */
function isNodeLocked(node: FileNode): boolean {
  if (node.lockedBy == null) return false;
  return node.lockExpireAt == null || new Date(node.lockExpireAt).getTime() > Date.now();
}

/** 是否可在线编辑：docx/xlsx/pptx/pdf 且当前用户具备编辑（上传）权限 */
function canEditNode(node: FileNode, has: (perm: string) => boolean): boolean {
  return node.nodeType === 1 && isEditableOfficeSuffix(node.suffix) && has('file:upload');
}

export interface FileBrowserDialogsProps {
  files: FileNode[];
  filteredFiles: FileNode[];
  selectedIds: Set<string>;
  clipboard: { nodeIds: string[]; mode: 'copy' | 'cut' } | null;
  enableShare: boolean;
  enableVersions: boolean;
  enableArchive: boolean;
  checkFav: (id: string) => boolean;
  has: (perm: string) => boolean;
  onToggleLock?: (action: 'lock' | 'unlock', node: FileNode) => void;
  source: FileSource;
  parentId: string | null;
  zipProgress: number | null;
  downloadQueuedCount: number | null;
  setDownloadQueuedCount: Dispatch<SetStateAction<number | null>>;
  dragRect: { startX: number; currentX: number; startY: number; currentY: number } | null;
  showCreateFolder: boolean;
  setShowCreateFolder: Dispatch<SetStateAction<boolean>>;
  newFileType: BlankFileType | null;
  setNewFileType: Dispatch<SetStateAction<BlankFileType | null>>;
  showBatchRename: boolean;
  setShowBatchRename: Dispatch<SetStateAction<boolean>>;
  archiveTarget: FileNode | null;
  setArchiveTarget: Dispatch<SetStateAction<FileNode | null>>;
  renameTarget: FileNode | null;
  setRenameTarget: Dispatch<SetStateAction<FileNode | null>>;
  convertTarget: FileNode | null;
  setConvertTarget: Dispatch<SetStateAction<FileNode | null>>;
  moveTarget: { nodeIds: string[]; mode: 'move' | 'copy' } | null;
  setMoveTarget: Dispatch<SetStateAction<{ nodeIds: string[]; mode: 'move' | 'copy' } | null>>;
  shareTarget: FileNode | null;
  setShareTarget: Dispatch<SetStateAction<FileNode | null>>;
  downloadTarget: FileNode | null;
  setDownloadTarget: Dispatch<SetStateAction<FileNode | null>>;
  versionTarget: FileNode | null;
  setVersionTarget: Dispatch<SetStateAction<FileNode | null>>;
  preview: { files: FileNode[]; index: number } | null;
  setPreview: Dispatch<SetStateAction<{ files: FileNode[]; index: number } | null>>;
  contextMenu: { x: number; y: number; node: FileNode } | null;
  setContextMenu: Dispatch<SetStateAction<{ x: number; y: number; node: FileNode } | null>>;
  blankContextMenu: { x: number; y: number } | null;
  setBlankContextMenu: Dispatch<SetStateAction<{ x: number; y: number } | null>>;
  handleContextAction: (action: string, node: FileNode) => void | Promise<void>;
  handleCreateFile: (type: BlankFileType, fileName: string) => Promise<void>;
  handleUploadClick: () => void;
  handleNewFile: (type: BlankFileType) => void;
  handleArchiveExtracted: (folderId: string) => void;
  fetchFiles: () => void;
  clearSelection: () => void;
  selectAll: () => void;
  paste: () => void;
  showToast: (msg: string, type?: 'success' | 'error' | 'info' | 'warning') => void;
  onNavigateFolder: (node: FileNode) => void;
}

export default function FileBrowserDialogs(props: FileBrowserDialogsProps) {
  const navigate = useNavigate();
  const {
    filteredFiles, selectedIds, clipboard,
    enableShare, enableVersions, enableArchive, checkFav, has, onToggleLock,
    source, parentId, zipProgress, downloadQueuedCount, setDownloadQueuedCount,
    dragRect, showCreateFolder, setShowCreateFolder, newFileType, setNewFileType,
    showBatchRename, setShowBatchRename, archiveTarget, setArchiveTarget,
    renameTarget, setRenameTarget, convertTarget, setConvertTarget,
    moveTarget, setMoveTarget, shareTarget, setShareTarget,
    downloadTarget, setDownloadTarget, versionTarget, setVersionTarget,
    preview, setPreview, contextMenu, setContextMenu,
    blankContextMenu, setBlankContextMenu,
    handleContextAction, handleCreateFile, handleUploadClick, handleNewFile,
    handleArchiveExtracted, fetchFiles, clearSelection, selectAll, paste,
    showToast,
  } = props;

  return (
    <>
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
          showEdit={canEditNode(contextMenu.node, has)}
          showArchive={enableArchive}
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
          onExtracted={handleArchiveExtracted}
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

      {downloadQueuedCount !== null && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50 overscroll-contain animate-fade-in"
          role="presentation"
          onClick={() => setDownloadQueuedCount(null)}
        >
          <div
            className="bg-surface rounded-md shadow-lg w-[420px] animate-dialog-pop p-6 text-center"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-12 h-12 rounded-full bg-green-500/15 flex items-center justify-center mx-auto mb-3">
              <CheckCircle2 className="w-6 h-6 text-green-500" />
            </div>
            <p className="text-sm font-medium text-fg mb-5">
              已添加 {downloadQueuedCount} 个下载任务
            </p>
            <div className="flex justify-center gap-2">
              <button onClick={() => setDownloadQueuedCount(null)} className="btn-secondary">
                关闭
              </button>
              <button
                onClick={() => {
                  setDownloadQueuedCount(null);
                  navigate('/transfers');
                }}
                className="btn-primary flex items-center gap-1.5"
              >
                <ListChecks className="w-4 h-4" />
                查看传输列表
              </button>
            </div>
          </div>
        </div>
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

      {zipProgress !== null && (
        <div className="fixed top-16 right-4 z-[110] w-64 bg-surface rounded-md border border-border shadow-md p-3">
          <p className="text-xs font-medium text-fg mb-1.5 flex items-center gap-1.5">
            <Loader2 className="w-3.5 h-3.5 text-primary-600 animate-spin" aria-hidden />
            正在打包下载
          </p>
          <p className="text-[10px] text-muted">
            {zipProgress > 0 ? `已下载 ${formatSize(zipProgress)}` : '准备中…'}
          </p>
        </div>
      )}
    </>
  );
}
