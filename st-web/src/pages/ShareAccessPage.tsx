import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Lock, Download, FolderClosed, ArrowLeft, Cloud, ChevronRight, Folder, Eye } from 'lucide-react';
import api from '../lib/api';
import { formatSize, getFileTypeConfig, formatDate, isPreviewable } from '../lib/utils';
import FileTypeIcon from '../components/file/FileTypeIcon';
import PreviewModal from '../components/preview/PreviewModal';
import type { ShareAccessVO, ShareFileItem, FileNode } from '../types';

interface BreadcrumbItem {
  id: string;
  name: string;
}

export default function ShareAccessPage() {
  const { shareCode } = useParams<{ shareCode: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const urlPwd = searchParams.get('pwd') || '';

  const [password, setPassword] = useState(urlPwd);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [fileInfo, setFileInfo] = useState<ShareAccessVO | null>(null);

  // folder browsing state
  const [fileList, setFileList] = useState<ShareFileItem[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [breadcrumbs, setBreadcrumbs] = useState<BreadcrumbItem[]>([]);
  const [previewIndex, setPreviewIndex] = useState<number | null>(null);

  const accessShare = useCallback(
    async (pwd: string) => {
      setLoading(true);
      setError('');
      try {
        const data: ShareAccessVO = await api.post('/share/access/access', {
          shareCode,
          password: pwd || undefined,
        });
        setFileInfo(data);
        // if folder, load root children
        if (data.fileType === 0) {
          setBreadcrumbs([{ id: data.fileNodeId, name: data.fileName }]);
          loadFiles(data.fileNodeId, pwd);
        }
      } catch (e) {
        setError((e instanceof Error ? e.message : '') || '访问失败');
      } finally {
        setLoading(false);
      }
    },
    [shareCode],
  );

  // Auto-access if password is in URL
  useEffect(() => {
    if (urlPwd && !fileInfo) {
      accessShare(urlPwd);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadFiles = async (parentId: string, pwd?: string) => {
    setListLoading(true);
    try {
      const data: ShareFileItem[] = await api.get('/share/access/list', {
        params: { shareCode, parentId, password: pwd || password || undefined },
      });
      setFileList(data);
    } catch (e) {
      setError((e instanceof Error ? e.message : '') || '加载文件列表失败');
    } finally {
      setListLoading(false);
    }
  };

  const handleAccess = () => accessShare(password);

  const handleDownload = async (nodeId?: string) => {
    try {
      const url: string = await api.get(`/share/access/download/${shareCode}`, {
        params: { nodeId, password: password || undefined },
      });
      window.open(url, '_blank');
    } catch (e) {
      setError((e instanceof Error ? e.message : '') || '下载失败');
    }
  };

  const handleFolderClick = (folder: ShareFileItem) => {
    setBreadcrumbs((prev) => [...prev, { id: folder.id, name: folder.name }]);
    setError('');
    loadFiles(folder.id);
  };

  const handleBreadcrumbClick = (index: number) => {
    const newCrumbs = breadcrumbs.slice(0, index + 1);
    setBreadcrumbs(newCrumbs);
    setError('');
    loadFiles(newCrumbs[newCrumbs.length - 1].id);
  };

  const config = fileInfo ? getFileTypeConfig(fileInfo.fileType, fileInfo.suffix) : null;
  const isFolder = fileInfo?.fileType === 0;

  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-surface-2">
      <button
        onClick={() => navigate('/login')}
        className="absolute top-6 left-6 flex items-center gap-1.5 text-sm text-muted hover:text-fg transition-colors cursor-pointer"
      >
        <ArrowLeft className="w-4 h-4" aria-hidden />
        返回登录
      </button>

      <div className={`w-full ${isFolder ? 'max-w-3xl' : 'max-w-md'}`}>
        <div className="bg-surface rounded-xl shadow-md border border-border overflow-hidden">
          {/* Header */}
          <div className="flex items-center gap-3 px-6 py-5 border-b border-border">
            <div className="w-10 h-10 bg-primary-600 rounded-lg flex items-center justify-center">
              <Cloud className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-lg font-semibold text-fg">分享文件</h1>
              <p className="text-xs text-muted">星云盘 文件分享</p>
            </div>
          </div>

          {fileInfo ? (
            isFolder ? (
              /* Folder browsing view */
              <div className="flex flex-col" style={{ maxHeight: '70vh' }}>
                {/* Breadcrumb */}
                <div className="flex items-center gap-1 px-6 py-3 border-b border-border overflow-x-auto">
                  {breadcrumbs.map((crumb, i) => (
                    <div key={crumb.id} className="flex items-center gap-1 shrink-0">
                      {i > 0 && <ChevronRight className="w-3.5 h-3.5 text-muted" />}
                      <button
                        onClick={() => handleBreadcrumbClick(i)}
                        className={`text-sm transition-colors cursor-pointer ${
                          i === breadcrumbs.length - 1
                            ? 'text-fg font-medium'
                            : 'text-muted hover:text-primary-600'
                        }`}
                      >
                        {i === 0 && <Folder className="w-3.5 h-3.5 inline mr-1 -mt-0.5" />}
                        {crumb.name}
                      </button>
                    </div>
                  ))}
                </div>

                {/* Download all button */}
                {fileInfo.permission >= 1 && (
                  <div className="px-6 py-2.5 border-b border-border flex items-center justify-between">
                    <span className="text-xs text-muted">
                      共 {fileList.length} 个项目
                    </span>
                    <button
                      onClick={() => handleDownload()}
                      className="flex items-center gap-1.5 text-xs text-primary-600 hover:text-primary-600 cursor-pointer"
                    >
                      <Download className="w-3.5 h-3.5" aria-hidden />
                      下载整个文件夹
                    </button>
                  </div>
                )}

                {/* Error */}
                {error && (
                  <p className="text-xs text-red-600 dark:text-red-400 bg-red-500/15 px-6 py-2">{error}</p>
                )}

                {/* File list */}
                <div className="flex-1 overflow-y-auto">
                  {listLoading ? (
                    <div className="flex items-center justify-center py-12">
                      <div className="w-6 h-6 border-2 border-border border-t-primary-500 rounded-full animate-spin" />
                    </div>
                  ) : fileList.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-12 text-muted">
                      <FolderClosed className="w-10 h-10 mb-2" />
                      <p className="text-sm">空文件夹</p>
                    </div>
                  ) : (
                    fileList.map((file) => {
                      const cfg = getFileTypeConfig(file.nodeType, file.suffix);
                      const previewable = file.nodeType === 1 && isPreviewable(file.suffix);
                      const canDownload = file.nodeType === 1 && fileInfo.permission >= 1;
                      return (
                        <div
                          key={file.id}
                          onClick={() => {
                            if (file.nodeType === 0) {
                              handleFolderClick(file);
                            } else if (previewable) {
                              const fileIdx = fileList.filter(f => f.nodeType === 1).findIndex(f => f.id === file.id);
                              setPreviewIndex(fileIdx);
                            } else if (canDownload) {
                              handleDownload(file.id);
                            }
                          }}
                          className={`flex items-center gap-3 px-6 py-2.5 border-b border-border transition-colors ${
                            file.nodeType === 0 || previewable || canDownload
                              ? 'hover:bg-surface-2 cursor-pointer'
                              : ''
                          }`}
                        >
                          <FileTypeIcon config={cfg} size="sm" suffix={file.suffix} />
                          <div className="flex-1 min-w-0">
                            <p className="text-sm text-fg truncate">{file.name}</p>
                          </div>
                          <div className="flex items-center gap-2 shrink-0">
                            <span className="text-xs text-muted w-20 text-right">
                              {file.nodeType === 0 ? '-' : formatSize(file.fileSize)}
                            </span>
                            <span className="text-xs text-muted w-28 text-right hidden sm:block">
                              {formatDate(file.createdAt)}
                            </span>
                            {previewable && (
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  const fileIdx = fileList.filter(f => f.nodeType === 1).findIndex(f => f.id === file.id);
                                  setPreviewIndex(fileIdx);
                                }}
                                className="p-1 text-muted hover:text-primary-600 cursor-pointer transition-colors"
                                title="预览" aria-label="预览"
                              >
                                <Eye className="w-4 h-4" aria-hidden />
                              </button>
                            )}
                            {canDownload && (
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleDownload(file.id);
                                }}
                                className="p-1 text-muted hover:text-primary-600 cursor-pointer transition-colors"
                                title="下载" aria-label="下载"
                              >
                                <Download className="w-4 h-4" aria-hidden />
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>

                {/* Back button */}
                <div className="px-6 py-3 border-t border-border">
                  <button
                    onClick={() => {
                      setFileInfo(null);
                      setFileList([]);
                      setBreadcrumbs([]);
                    }}
                    className="text-sm text-muted hover:text-fg transition-colors cursor-pointer"
                  >
                    返回
                  </button>
                </div>
              </div>
            ) : (
              /* Single file view */
              <div className="p-6 space-y-5">
                <div className="flex items-center gap-4 bg-surface-2 rounded-lg p-4">
                  {config && <FileTypeIcon config={config} size="lg" suffix={fileInfo.suffix} />}
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-fg truncate">
                      {fileInfo.fileName}
                    </p>
                    <p className="text-xs text-muted mt-0.5">
                      {fileInfo.size ? formatSize(parseInt(fileInfo.size)) : '-'} ·{' '}
                      {fileInfo.suffix || '文件'}
                    </p>
                  </div>
                </div>

                {error && <p className="text-xs text-red-600 dark:text-red-400 bg-red-500/15 rounded-md p-2">{error}</p>}

                {isPreviewable(fileInfo.suffix) && (
                  <button
                    onClick={() => setPreviewIndex(0)}
                    className="w-full flex items-center justify-center gap-2 py-3 bg-neutral-800 text-white text-sm font-medium rounded-md hover:bg-neutral-900 transition-colors cursor-pointer"
                  >
                    <Eye className="w-4 h-4" aria-hidden />
                    预览文件
                  </button>
                )}

                {fileInfo.permission >= 1 && (
                  <button
                    onClick={() => handleDownload()}
                    className="w-full flex items-center justify-center gap-2 py-3 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
                  >
                    <Download className="w-4 h-4" aria-hidden />
                    下载文件
                  </button>
                )}

                {fileInfo.permission === 0 && !isPreviewable(fileInfo.suffix) && (
                  <p className="text-center text-xs text-muted">此分享仅支持查看，不可下载</p>
                )}

                <button
                  onClick={() => setFileInfo(null)}
                  className="w-full py-2.5 text-sm text-muted hover:text-fg transition-colors cursor-pointer"
                >
                  返回
                </button>
              </div>
            )
          ) : (
            /* Password input form */
            <div className="p-6 space-y-5">
              <div className="flex flex-col items-center text-center py-4">
                <div className="w-12 h-12 rounded-full bg-primary-500/10 flex items-center justify-center mb-3">
                  <Lock className="w-5 h-5 text-primary-600" />
                </div>
                <p className="text-sm text-muted">请输入提取码以访问分享文件</p>
              </div>

              {error && (
                <p className="text-xs text-red-600 dark:text-red-400 bg-red-500/15 rounded-md p-2.5 text-center">
                  {error}
                </p>
              )}

              <input
                type="text"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAccess()}
                placeholder="提取码"
                autoFocus
                className="w-full px-4 py-3 text-center text-lg tracking-widest text-fg bg-surface border border-border rounded-md outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 placeholder:text-muted placeholder:text-sm placeholder:tracking-normal"
              />

              <button
                onClick={handleAccess}
                disabled={loading}
                className="w-full py-3 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50"
              >
                {loading ? '验证中…' : '访问文件'}
              </button>

              <button
                onClick={handleAccess}
                className="w-full text-xs text-muted hover:text-fg transition-colors cursor-pointer"
              >
                公开分享？直接访问 -&gt;
              </button>
            </div>
          )}
        </div>

        <p className="text-center text-xs text-muted mt-4">文件由星云盘安全加密分享</p>
      </div>

      {previewIndex !== null && fileInfo && (
        <PreviewModal
          files={
            fileInfo.fileType === 0
              ? (fileList.filter(f => f.nodeType === 1) as unknown as FileNode[])
              : ([{
                  id: fileInfo.fileNodeId,
                  parentId: '0',
                  nodeType: 1,
                  name: fileInfo.fileName,
                  path: '',
                  fileSize: fileInfo.size,
                  suffix: fileInfo.suffix,
                  contentType: null,
                  status: 0,
                  thumbnailPath: null,
                  createdAt: '',
                  updatedAt: '',
                }] as FileNode[])
          }
          currentIndex={previewIndex}
          onClose={() => setPreviewIndex(null)}
          shareContext={{ shareCode: shareCode!, password: password || undefined }}
          onDownload={(file) => handleDownload(file.id)}
        />
      )}
    </div>
  );
}
