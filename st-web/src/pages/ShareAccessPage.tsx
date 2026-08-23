import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Lock, Download, FolderClosed, ArrowLeft, Cloud, ChevronRight, Folder, Eye, Edit3 } from 'lucide-react';
import api from '../lib/api';
import { formatSize, getFileTypeConfig, formatDate, isPreviewable, isPdf } from '../lib/utils';
import { isEditableOfficeSuffix } from '../lib/editor';
import FileTypeIcon from '../components/file/FileTypeIcon';
import PreviewModal from '../components/preview/PreviewModal';
import type { ShareAccessVO, ShareFileItem, FileNode, ShareCaptcha } from '../types';

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
  // 是否需要提取码：自动访问失败（私密分享无码/错误码）才显示提取码表单
  const [needsPassword, setNeedsPassword] = useState(false);
  // 是否需图形验证码（失败达阈值后由后端返回 3006 触发）
  const [needCaptcha, setNeedCaptcha] = useState(false);
  const [captchaId, setCaptchaId] = useState('');
  const [captchaImage, setCaptchaImage] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');

  // folder browsing state
  const [fileList, setFileList] = useState<ShareFileItem[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [breadcrumbs, setBreadcrumbs] = useState<BreadcrumbItem[]>([]);
  const [previewIndex, setPreviewIndex] = useState<number | null>(null);

  const loadFiles = useCallback(
    async (parentId: string, pwd?: string) => {
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
    },
    [shareCode, password],
  );

  const loadCaptcha = useCallback(async () => {
    try {
      const data: ShareCaptcha = await api.get('/share/captcha');
      setCaptchaId(data.captchaId);
      setCaptchaImage(data.imageBase64);
      setCaptchaCode('');
    } catch {
      /* 验证码加载失败不阻塞，仅提示 */
    }
  }, []);

  const accessShare = useCallback(
    async (pwd: string) => {
      setLoading(true);
      setError('');
      try {
        const data: ShareAccessVO = await api.post('/share/access/access', {
          shareCode,
          password: pwd || undefined,
          captchaId: needCaptcha ? captchaId : undefined,
          captchaCode: needCaptcha ? captchaCode : undefined,
        });
        setFileInfo(data);
        setNeedCaptcha(false);
        // if folder, load root children
        if (data.fileType === 0) {
          setBreadcrumbs([{ id: data.fileNodeId, name: data.fileName }]);
          loadFiles(data.fileNodeId, pwd);
        }
      } catch (e) {
        const code = (e as { code?: number }).code;
        const msg = (e instanceof Error ? e.message : '') || '访问失败';
        setError(msg);
        if (code === 3006) {
          // 需要验证码：展示验证码输入
          setNeedCaptcha(true);
          setNeedsPassword(true);
          loadCaptcha();
        } else if (code === 3005) {
          // 尝试次数过多：锁定提示，不再要求输入
          setError('尝试次数过多，请稍后再试');
          setNeedsPassword(true);
        } else {
          setNeedsPassword(true);
          setNeedCaptcha(false);
        }
      } finally {
        setLoading(false);
      }
    },
    [shareCode, loadFiles, needCaptcha, captchaId, captchaCode, loadCaptcha],
  );

  // 进入页面自动访问：公开分享无需提取码直接进入；私密分享带 pwd 参数也直接尝试；
  // 仅当访问被拒（需提取码）时才显示提取码表单
  useEffect(() => {
    if (!fileInfo && !needsPassword) {
      accessShare(urlPwd || '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileInfo]);

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

  /** 是否走 OnlyOffice 查看/编辑（Office 可编辑格式 + PDF） */
  const isOnlyOfficePreview = (suffix: string | null | undefined) =>
    isEditableOfficeSuffix(suffix) || isPdf(suffix);

  /** 打开分享 OnlyOffice 查看/编辑页（全屏 + 返回按钮；编辑/只读由分享权限集决定） */
  const openEditorPreview = (nodeId: string) => {
    const pwd = password || urlPwd;
    const params = new URLSearchParams({
      nodeId,
      from: `/share/${shareCode}${pwd ? `?pwd=${encodeURIComponent(pwd)}` : ''}`,
    });
    if (pwd) params.set('password', pwd);
    navigate(`/share/${shareCode}/editor?${params.toString()}`);
  };

  /** 分享权限集（JSON 权威；旧数据无 JSON 时回退单值 permission） */
  const sharePerms = useMemo(() => {
    if (!fileInfo?.permissions) return null;
    try {
      return JSON.parse(fileInfo.permissions) as Record<string, boolean>;
    } catch {
      return null;
    }
  }, [fileInfo]);
  const canDownloadShare = sharePerms ? Boolean(sharePerms.download) : (fileInfo?.permission ?? 0) >= 1;
  const canEditShare = Boolean(sharePerms?.edit) || (fileInfo?.permission ?? 0) >= 3;

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
                      const canDownload = file.nodeType === 1 && canDownloadShare;
                      return (
                        <div
                          key={file.id}
                          onClick={() => {
                            if (file.nodeType === 0) {
                              handleFolderClick(file);
                            } else if (previewable) {
                              if (isOnlyOfficePreview(file.suffix)) {
                                openEditorPreview(file.id);
                                return;
                              }
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
                                  if (isOnlyOfficePreview(file.suffix)) {
                                    openEditorPreview(file.id);
                                    return;
                                  }
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
                            {isEditableOfficeSuffix(file.suffix) && canEditShare && (
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  openEditorPreview(file.id);
                                }}
                                className="p-1 text-muted hover:text-primary-600 cursor-pointer transition-colors"
                                title="在线编辑" aria-label="在线编辑"
                              >
                                <Edit3 className="w-4 h-4" aria-hidden />
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
                    onClick={() =>
                      isOnlyOfficePreview(fileInfo.suffix)
                        ? openEditorPreview(String(fileInfo.fileNodeId))
                        : setPreviewIndex(0)
                    }
                    className="w-full flex items-center justify-center gap-2 py-3 bg-neutral-800 text-white text-sm font-medium rounded-md hover:bg-neutral-900 transition-colors cursor-pointer"
                  >
                    <Eye className="w-4 h-4" aria-hidden />
                    预览文件
                  </button>
                )}

                {isEditableOfficeSuffix(fileInfo.suffix) && canEditShare && (
                  <button
                    onClick={() => openEditorPreview(String(fileInfo.fileNodeId))}
                    className="w-full flex items-center justify-center gap-2 py-3 bg-neutral-800 text-white text-sm font-medium rounded-md hover:bg-neutral-900 transition-colors cursor-pointer"
                  >
                    <Edit3 className="w-4 h-4" aria-hidden />
                    在线编辑
                  </button>
                )}

                {canDownloadShare && (
                  <button
                    onClick={() => handleDownload()}
                    className="w-full flex items-center justify-center gap-2 py-3 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
                  >
                    <Download className="w-4 h-4" aria-hidden />
                    下载文件
                  </button>
                )}

                {!canDownloadShare && !isPreviewable(fileInfo.suffix) && (
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
          ) : needsPassword ? (
            /* Password input form：仅私密分享（自动访问被拒）时显示 */
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

              {needCaptcha && (
                <div className="flex items-center gap-3">
                  {captchaImage && (
                    <img
                      src={captchaImage}
                      alt="验证码"
                      title="点击刷新"
                      onClick={loadCaptcha}
                      className="h-10 rounded-md border border-border cursor-pointer"
                    />
                  )}
                  <input
                    type="text"
                    value={captchaCode}
                    onChange={(e) => setCaptchaCode(e.target.value)}
                    maxLength={4}
                    placeholder="验证码"
                    className="flex-1 px-3 py-2.5 text-center text-base tracking-widest text-fg bg-surface border border-border rounded-md outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 placeholder:text-muted placeholder:text-sm placeholder:tracking-normal"
                  />
                </div>
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
          ) : (
            /* 自动访问中：公开分享/带码私密分享不应出现提取码界面 */
            <div className="p-6 flex flex-col items-center justify-center gap-3 py-12">
              <div className="w-8 h-8 border-2 border-border border-t-primary-500 rounded-full animate-spin" />
              <p className="text-sm text-muted">正在访问分享…</p>
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
