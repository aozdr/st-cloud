import { RefreshCw, Loader2, FolderInput } from 'lucide-react';
import { cn } from '../../lib/utils';
import FileToolbar from './FileToolbar';
import FileBreadcrumb from './FileBreadcrumb';
import FileList from './FileList';
import { useFolderSizes } from '../../hooks/useFolderSizes';
import MultiSelectBar from '../ui/MultiSelectBar';
import { EmptyState } from './Dialogs';
import GenericEmptyState from '../EmptyState';
import FileDetailPanel from './FileDetailPanel';
import FileBrowserDialogs from './FileBrowserDialogs';
import FileBrowserPagination from './FileBrowserPagination';
import { useFileBrowser, type FileBrowserProps } from '../../hooks/useFileBrowser';

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
  detailOpen,
  onCloseDetail,
  onToggleLock,
}: FileBrowserProps) {
  const {
    files, loading, loadError, view, setView, iconSize, setIconSize,
    dragOverFolderId, zipProgress, downloadQueuedCount, setDownloadQueuedCount,
    page, total, refreshKey, pageSize, pageInput, setPageInput, isRefreshing,
    sortBy, sortDir, foldersFirst, currentPath, pathSegments,
    filteredFiles, selectedIds, focusedId, clipboard, cutIds,
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
    handleCreateFile, enterPathEditMode, handlePathSubmit, navigateToPath,
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
  } = useFileBrowser({
    source, parentId, onNavigateFolder, onBack, uploadSpaceId, enableShare,
    enableVersions, syncUrl, categoryLabel, focusId, onOpenDetail, onToggleLock,
  });

  // 文件夹大小批量懒加载（去抖），列表表格/网格显示文件夹总占用
  const folderSizes = useFolderSizes(filteredFiles);

  /** 详情是否打开：页面级详情走 detailOpen prop；未传时回退到内部详情状态 */
  const listDetailOpen = onOpenDetail ? (detailOpen ?? false) : !!detailFile;

  return (
    <div
      className="flex flex-col h-full bg-[#FEFEFD] dark:bg-surface overflow-hidden"
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

      <div className="flex-1 min-h-0 flex overflow-hidden">
      <div
        ref={fileListRef}
        className={cn('flex-1 min-h-0 min-w-0 overflow-y-auto relative', listDetailOpen && 'detail-open')}
        onTouchStart={isMobile ? ptr.onTouchStart : undefined}
        onTouchMove={isMobile ? ptr.onTouchMove : undefined}
        onTouchEnd={isMobile ? ptr.onTouchEnd : undefined}
        onMouseDown={(e) => {
          if (e.button !== 0 || bandRef.current?.contains(e.target as Node) || isFileItemClick(e)) return;
          e.preventDefault();
          const active = document.activeElement as HTMLElement | null;
          if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) {
            active.blur();
          }
          startDrag(e.clientX, e.clientY);
          clearSelection();
          if (contextMenu) setContextMenu(null);
          // 点击列表空白：关闭浮层详情（内部详情 setDetailFile；页面级详情走 onCloseDetail）
          if (!onOpenDetail) setDetailFile(null);
          onCloseDetail?.();
        }}
        onContextMenu={(e) => {
          if (bandRef.current?.contains(e.target as Node) || isFileItemClick(e)) return;
          e.preventDefault();
          clearSelection();
          setContextMenu(null);
          setBlankContextMenu({ x: e.clientX, y: e.clientY });
        }}
      >
        <div ref={bandRef} className="bg-[#FEFEFD] dark:bg-surface px-5 md:px-8 pt-3 pb-3">
          <FileToolbar
            refreshing={isRefreshing}
            has={has}
            selectedCount={selectedIds.size}
            filesCount={files.length}
            allSelected={allSelected}
            selectedSize={selectedSize}
            canEditSelected={!!editableSelected}
            onEdit={handleToolbarEdit}
            sortBy={sortBy}
            onSortChange={handleToolbarSortChange}
            sortDir={sortDir}
            onSortDirToggle={handleSortDirToggle}
            view={view}
            onViewChange={setView}
            onNewFolder={handleNewFolderClick}
            onNewFile={handleNewFile}
            onUploadClick={handleUploadClick}
            onDownload={handleToolbarDownload}
            onMove={handleToolbarMove}
            onCopy={handleToolbarCopy}
            onDelete={handleToolbarDelete}
            onSelectAll={selectAll}
            onClearSelection={clearSelection}
            onRefresh={refresh}
            onBatchRename={handleBatchRenameClick}
            foldersFirst={foldersFirst}
            onToggleFoldersFirst={handleToggleFoldersFirst}
          />
          <div className="mt-2 flex items-center gap-2">
            <div className="flex-1 min-w-0">
              {categoryLabel ? (
                <div className="flex items-baseline gap-2 min-w-0">
                  <h1 className="text-lg font-semibold text-fg truncate">{categoryLabel}</h1>
                  <span className="text-xs text-tertiary whitespace-nowrap">{loading ? '加载中…' : `${total} 项`}</span>
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
                  pathSegments={pathSegments}
                  onNavigateToPath={navigateToPath}
                  pathInputRef={pathInputRef}
                />
              )}
            </div>
            {view === 'grid' && (
              <div className="flex items-center bg-surface-2 rounded-lg p-0.5 flex-shrink-0" role="group" aria-label="图标大小">
                {([
                  ['sm', '小'],
                  ['md', '中'],
                  ['lg', '大'],
                ] as const).map(([value, label]) => (
                  <button
                    key={value}
                    onClick={() => setIconSize(value)}
                    aria-label={`${label}图标`}
                    title={`${label}图标`}
                    className={cn(
                      'h-7 min-w-7 px-1.5 rounded-md text-xs cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      iconSize === value ? 'bg-surface text-primary-600 shadow-soft font-medium' : 'text-muted hover:text-fg',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
        <div className="bg-[#FEFEFD] dark:bg-surface px-5 md:px-8 pt-1 pb-8">
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
          <div className="bg-[#FEFEFD] dark:bg-surface overflow-hidden">
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
            <div className="flex flex-col items-center justify-center h-full text-muted">
              <Loader2 className="w-6 h-6 animate-spin mb-3" aria-hidden />
              <span className="text-sm">加载中…</span>
            </div>
        ) : loadError ? (
          <GenericEmptyState
            type="generic"
            title="加载失败"
            description="网络异常或服务暂不可用，请检查后重试"
            action={<button onClick={() => fetchFiles()} className="btn-primary">重试</button>}
          />
        ) : files.length === 0 ? (
          <EmptyState
            onCreateFolder={() => setShowCreateFolder(true)}
            onCreateUpload={has('file:upload') ? handleUploadClick : undefined}
          />
        ) : (
          <div key={`${refreshKey}:${parentId}`} className="animate-file-enter">
            <FileList
              view={view}
              iconSize={iconSize}
              scrollRef={fileListRef}
              files={filteredFiles}
              folderSizes={folderSizes}
              lockedIds={lockedIds}
              selectedIds={selectedIds}
              focusedId={focusedId}
              cutIds={cutIds}
              sortBy={sortBy}
              sortDir={sortDir}
              onSortChange={handleSortChange}
              onSelect={handleSelect}
              onToggleSelect={toggleSelect}
              onSelectAll={selectAll}
              isFavorite={checkFav}
              onToggleFavorite={handleToggleFavorite}
              onContextMenu={handleContextMenu}
              onNavigate={handleListNavigate}
              onDoubleClick={handleListDoubleClick}
              onItemDragStart={handleItemDragStart}
              onFolderDragOver={handleFolderDragOver}
              onFolderDragLeave={handleFolderDragLeave}
              onFolderDrop={handleFolderDrop}
              dragOverFolderId={dragOverFolderId}
            />
          </div>
        )}
          </div>
        </div>
      </div>
      {!onOpenDetail && detailFile && (
        <FileDetailPanel file={detailFile} onClose={() => setDetailFile(null)} />
      )}
      </div>

      <FileBrowserPagination
        total={total}
        pageSize={pageSize}
        page={page}
        totalPages={totalPages}
        pageInput={pageInput}
        setPageInput={setPageInput}
        onPageInputCommit={handlePageInputCommit}
        onPageSizeChange={handlePageSizeChange}
        onPrev={handlePrevPage}
        onNext={handleNextPage}
      />

      <FileBrowserDialogs
        files={files}
        filteredFiles={filteredFiles}
        selectedIds={selectedIds}
        clipboard={clipboard}
        enableShare={enableShare}
        enableVersions={enableVersions}
        enableArchive={enableArchive}
        checkFav={checkFav}
        has={has}
        onToggleLock={onToggleLock}
        source={source}
        parentId={parentId}
        zipProgress={zipProgress}
        downloadQueuedCount={downloadQueuedCount}
        setDownloadQueuedCount={setDownloadQueuedCount}
        dragRect={dragRect}
        showCreateFolder={showCreateFolder}
        setShowCreateFolder={setShowCreateFolder}
        newFileType={newFileType}
        setNewFileType={setNewFileType}
        showBatchRename={showBatchRename}
        setShowBatchRename={setShowBatchRename}
        archiveTarget={archiveTarget}
        setArchiveTarget={setArchiveTarget}
        renameTarget={renameTarget}
        setRenameTarget={setRenameTarget}
        convertTarget={convertTarget}
        setConvertTarget={setConvertTarget}
        moveTarget={moveTarget}
        setMoveTarget={setMoveTarget}
        shareTarget={shareTarget}
        setShareTarget={setShareTarget}
        downloadTarget={downloadTarget}
        setDownloadTarget={setDownloadTarget}
        versionTarget={versionTarget}
        setVersionTarget={setVersionTarget}
        preview={preview}
        setPreview={setPreview}
        contextMenu={contextMenu}
        setContextMenu={setContextMenu}
        blankContextMenu={blankContextMenu}
        setBlankContextMenu={setBlankContextMenu}
        handleContextAction={handleContextAction}
        handleCreateFile={handleCreateFile}
        handleUploadClick={handleUploadClick}
        handleNewFile={handleNewFile}
        handleArchiveExtracted={handleArchiveExtracted}
        fetchFiles={fetchFiles}
        clearSelection={clearSelection}
        selectAll={selectAll}
        paste={paste}
        showToast={showToast}
        onNavigateFolder={onNavigateFolder}
      />
    </div>
  );
}
