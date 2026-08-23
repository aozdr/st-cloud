import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { X, Download, ChevronLeft, ChevronRight, RotateCw, ZoomIn, ZoomOut, Maximize2 } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import api, { buildStreamUrl } from '../../lib/api';
import { isElectron } from '../../lib/electron';
import { getServerUrlSync } from '../../lib/server-config';
import type { FileNode, PreviewResult } from '../../types';
import { addRecentFile } from '../../lib/recentFiles';
import { isImage, isVideo, isPdf, isAudio, isText, getFileTypeConfig, cn } from '../../lib/utils';
import { isEditableOfficeSuffix } from '../../lib/editor';

const PlyrPlayer = lazy(() => import('./PlyrPlayer'));

interface Props {
  files: FileNode[];
  currentIndex: number;
  onClose: () => void;
  shareContext?: { shareCode: string; password?: string };
  onDownload?: (file: FileNode) => void;
}

export default function PreviewModal({ files, currentIndex, onClose, shareContext, onDownload }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const [index, setIndex] = useState(currentIndex);
  // 图片缩放/旋转状态
  const [imgScale, setImgScale] = useState(1);
  const [imgRotate, setImgRotate] = useState(0);
  // 图片放大后的平移（拖拽查看）
  const [imgOffset, setImgOffset] = useState({ x: 0, y: 0 });
  const [imgDragging, setImgDragging] = useState(false);
  const imgDragRef = useRef<{ startX: number; startY: number; offsetX: number; offsetY: number } | null>(null);
  const panRef = useRef({ x: 0, y: 0 });
  const imgRef = useRef<HTMLImageElement>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [textContent, setTextContent] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const file = files[index];

  // 左右浏览仅支持媒体文件（图片/视频/音频）：非媒体文件打开时不可切换
  const previewFiles = useMemo(
    () => files.filter((f) => f.nodeType === 1 && (isImage(f.suffix) || isVideo(f.suffix) || isAudio(f.suffix))),
    [files],
  );
  const previewPos = previewFiles.findIndex((f) => f.id === file?.id);
  const goPrev = useCallback(() => {
    if (previewPos > 0) {
      const target = previewFiles[previewPos - 1];
      const idx = files.findIndex((f) => f.id === target.id);
      if (idx >= 0) setIndex(idx);
    }
  }, [previewPos, previewFiles, files]);
  const goNext = useCallback(() => {
    if (previewPos >= 0 && previewPos < previewFiles.length - 1) {
      const target = previewFiles[previewPos + 1];
      const idx = files.findIndex((f) => f.id === target.id);
      if (idx >= 0) setIndex(idx);
    }
  }, [previewPos, previewFiles, files]);

  useEffect(() => {
    if (file && file.nodeType === 1) addRecentFile(file);
  }, [file]);

  // 切换文件时重置图片变换状态
  useEffect(() => {
    setImgScale(1);
    setImgRotate(0);
    setImgOffset({ x: 0, y: 0 });
  }, [index]);

  useEffect(() => {
    if (!file || file.nodeType !== 1) return;
    // Office（docx/xlsx/pptx）与 PDF：一律走 OnlyOffice 只读查看（全屏页 + 返回按钮），
    // 不再使用 docx-preview/xlsx 本地渲染；分享场景跳转分享编辑器路由
    if (isEditableOfficeSuffix(file.suffix) || isPdf(file.suffix)) {
      const from = location.pathname + location.search;
      if (shareContext) {
        const params = new URLSearchParams({ nodeId: String(file.id), mode: 'view', from });
        if (shareContext.password) params.set('password', shareContext.password);
        navigate(`/share/${shareContext.shareCode}/editor?${params.toString()}`);
      } else {
        navigate(`/file/${file.id}/editor?mode=view&from=${encodeURIComponent(from)}`);
      }
      return;
    }
    setLoading(true);
    setUrl(null);
    setTextContent(null);

    if (shareContext) {
      // 分享模式：使用 stream 端点，无需登录 token，不会增加下载次数
      const params = new URLSearchParams({ nodeId: String(file.id) });
      if (shareContext.password) params.set('password', shareContext.password);
      const streamUrl = `${isElectron() ? getServerUrlSync() : ''}/api/share/access/stream/${shareContext.shareCode}?${params}`;
      setUrl(streamUrl);

      if (isText(file.suffix)) {
        fetch(streamUrl)
          .then((res) => res.text())
          .then((text) => {
            setTextContent(text.length > 500000 ? text.slice(0, 500000) + '\n\n… (内容过长，已截断)' : text);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else {
        setLoading(false);
      }
    } else {
      // 正常模式：需登录 token
      const token = localStorage.getItem('accessToken');
      const streamUrl = `${isElectron() ? getServerUrlSync() : ''}/api/file/${file.id}/stream`;
      const headers = { Authorization: `Bearer ${token}` };

      if (isText(file.suffix)) {
        fetch(streamUrl, { headers })
          .then((res) => res.text())
          .then((text) => {
            setTextContent(text.length > 500000 ? text.slice(0, 500000) + '\n\n… (内容过长，已截断)' : text);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else if (isImage(file.suffix)) {
        if (file.suffix?.toLowerCase() === 'gif') {
          // GIF：缩略图是静态首帧，必须用原文件流才能动起来
          api.post<{ token: string }>(`/file/${file.id}/download-token`)
            .then((d) => { setUrl(buildStreamUrl(file.id, { token: d.token, inline: true })); setLoading(false); })
            .catch(() => setLoading(false));
        } else {
          // 其它图片：通过预览 API 获取预签名缩略图（access token 无法用于 <img src>）
          api.get<string>(`/preview/${file.id}/thumbnail`, { params: { size: 'lg' } })
            .then((u) => { setUrl(u); setLoading(false); })
            .catch(() => setLoading(false));
        }
      } else if (isVideo(file.suffix) || isAudio(file.suffix)) {
        // 视频/音频：通过预览 API 获取预签名 URL（支持 Range 请求/拖动进度条）
        api.get<PreviewResult>(`/preview/${file.id}`)
          .then((data) => {
            if (data.url) {
              setUrl(data.url);
              setLoading(false);
            } else {
              // 后端不支持该格式预览，回退到下载令牌流
              return api.post<{ token: string }>(`/file/${file.id}/download-token`)
                .then((d) => {
                  setUrl(buildStreamUrl(file.id, { token: d.token, inline: true }));
                  setLoading(false);
                });
            }
          })
          .catch(() => setLoading(false));
      } else {
        // 不支持预览：获取下载令牌用于下载按钮
        api.post<{ token: string }>(`/file/${file.id}/download-token`)
          .then((data) => {
            setUrl(buildStreamUrl(file.id, { token: data.token, inline: true }));
            setLoading(false);
          })
          .catch(() => setLoading(false));
      }
    }
  }, [file, shareContext, location.pathname, location.search, navigate]);

  // Keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // 视频播放器（Plyr）内部的方向键交给播放器做快进/快退，不切换文件；
      // 输入类元素内同样跳过，避免误触文件切换
      const target = e.target as HTMLElement | null;
      const inFormField = !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable);
      const inMediaPlayer = !!target && (target.tagName === 'VIDEO' || target.tagName === 'AUDIO' || !!target.closest('.plyr'));
      if (e.key === 'Escape') {
        onClose();
      } else if (!inFormField && !inMediaPlayer && e.key === 'ArrowLeft') {
        goPrev();
      } else if (!inFormField && !inMediaPlayer && e.key === 'ArrowRight') {
        goNext();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [goPrev, goNext, onClose]);

  if (!file) return null;

  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const canPreview = isImage(file.suffix) || isVideo(file.suffix) || isPdf(file.suffix) || isAudio(file.suffix) || isText(file.suffix);

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-neutral-950/95 overscroll-contain animate-fade-in"
      onDragOver={(e) => e.stopPropagation()}
      onDrop={(e) => e.stopPropagation()}
    >
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-3 bg-neutral-950/80">
        <div className="flex items-center gap-3 min-w-0">
          <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0', config.bgColor)}>
            <config.icon className={cn('w-4 h-4', config.color)} aria-hidden />
          </div>
          <span className="text-white text-sm font-medium truncate">{file.name}</span>
        </div>
        <div className="flex items-center gap-2">
          {url && onDownload ? (
            <button
              onClick={() => onDownload(file)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white/80 hover:text-white hover:bg-white/10 rounded-lg cursor-pointer transition-colors"
            >
              <Download className="w-4 h-4" aria-hidden />
              <span>下载</span>
            </button>
          ) : url ? (
            <a
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white/80 hover:text-white hover:bg-white/10 rounded-lg cursor-pointer transition-colors"
            >
              <Download className="w-4 h-4" aria-hidden />
              <span>下载</span>
            </a>
          ) : null}
          <button onClick={onClose} aria-label="关闭" className="text-white/60 hover:text-white p-1.5 cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50 rounded-lg">
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 flex items-center justify-center relative overflow-hidden">
        {previewFiles.length > 1 && (
          <button
            onClick={goPrev} aria-label="上一个"
            disabled={previewPos <= 0}
            className="absolute left-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
          >
            <ChevronLeft className="w-6 h-6" aria-hidden />
          </button>
        )}

        <div className="max-w-[90%] max-h-[90%] flex items-center justify-center">
          {loading ? (
            <div className="w-10 h-10 border-3 border-white/30 border-t-white rounded-full animate-spin" />
          ) : !canPreview ? (
            <div className="text-center">
              <div className={cn('w-24 h-24 rounded-2xl flex items-center justify-center mx-auto mb-4', config.bgColor)}>
                <config.icon className={cn('w-10 h-10', config.color)} aria-hidden />
              </div>
              <p className="text-white/60 text-sm mb-3">此文件类型不支持在线预览</p>
              {url && onDownload ? (
                <button
                  onClick={() => onDownload(file)}
                  className="inline-flex items-center gap-2 px-4 py-2 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg cursor-pointer transition-colors"
                >
                  <Download className="w-4 h-4" aria-hidden />
                  <span>下载文件</span>
                </button>
              ) : url ? (
                <a
                  href={url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 px-4 py-2 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg cursor-pointer transition-colors"
                >
                  <Download className="w-4 h-4" aria-hidden />
                  <span>下载文件</span>
                </a>
              ) : null}
            </div>
          ) : isImage(file.suffix) && url ? (
            <div className="relative flex flex-col items-center">
              <img
                ref={imgRef}
                src={url}
                alt={file.name}
                width={800}
                height={600}
                loading="lazy"
                draggable={false}
                className={cn(
                  'max-w-full max-h-[80vh] object-contain rounded-lg select-none',
                  imgScale > 1 && 'cursor-grab touch-none',
                  imgDragging ? 'transition-none cursor-grabbing' : 'transition-transform duration-200',
                )}
                style={{
                  transform: `translate(${imgOffset.x}px, ${imgOffset.y}px) scale(${imgScale}) rotate(${imgRotate}deg)`,
                }}
                onDragStart={(e) => e.preventDefault()}
                onWheel={(e) => {
                  // 滚轮缩放：上滚放大，下滚缩小
                  e.preventDefault();
                  setImgScale((s) => Math.max(0.25, Math.min(5, s + (e.deltaY < 0 ? 0.15 : -0.15))));
                }}
                onPointerDown={(e) => {
                  // 放大后拖拽平移图片；1x 时不处理
                  if (imgScale <= 1) return;
                  e.preventDefault();
                  panRef.current = { x: imgOffset.x, y: imgOffset.y };
                  imgDragRef.current = {
                    startX: e.clientX,
                    startY: e.clientY,
                    offsetX: imgOffset.x,
                    offsetY: imgOffset.y,
                  };
                  setImgDragging(true);
                  (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
                }}
                onPointerMove={(e) => {
                  const drag = imgDragRef.current;
                  if (!drag) return;
                  // 拖动期间直接写 style（不经 React 重渲染），保证跟手
                  const x = drag.offsetX + (e.clientX - drag.startX);
                  const y = drag.offsetY + (e.clientY - drag.startY);
                  panRef.current = { x, y };
                  if (imgRef.current) {
                    imgRef.current.style.transform = `translate(${x}px, ${y}px) scale(${imgScale}) rotate(${imgRotate}deg)`;
                  }
                }}
                onPointerUp={() => {
                  imgDragRef.current = null;
                  setImgDragging(false);
                  setImgOffset({ ...panRef.current });
                }}
                onPointerCancel={() => {
                  imgDragRef.current = null;
                  setImgDragging(false);
                  setImgOffset({ ...panRef.current });
                }}
              />
              {/* 图片工具栏：缩放/旋转/重置 */}
              <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex items-center gap-1 bg-black/60 backdrop-blur-sm rounded-lg px-2 py-1.5">
                <button
                  onClick={() => setImgScale((s) => Math.max(0.25, s - 0.25))}
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                  aria-label="缩小"
                >
                  <ZoomOut className="w-4 h-4" />
                </button>
                <span className="text-white/60 text-xs tabular-nums w-12 text-center">{Math.round(imgScale * 100)}%</span>
                <button
                  onClick={() => setImgScale((s) => Math.min(5, s + 0.25))}
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                  aria-label="放大"
                >
                  <ZoomIn className="w-4 h-4" />
                </button>
                <div className="w-px h-5 bg-white/20 mx-1" />
                <button
                  onClick={() => setImgRotate((r) => r - 90)}
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                  aria-label="左旋转"
                >
                  <RotateCw className="w-4 h-4 scale-x-[-1]" />
                </button>
                <button
                  onClick={() => setImgRotate((r) => r + 90)}
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                  aria-label="右旋转"
                >
                  <RotateCw className="w-4 h-4" />
                </button>
                <div className="w-px h-5 bg-white/20 mx-1" />
                <button
                  onClick={() => { setImgScale(1); setImgRotate(0); setImgOffset({ x: 0, y: 0 }); }}
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                  aria-label="重置"
                >
                  <Maximize2 className="w-4 h-4" />
                </button>
                <div className="w-px h-5 bg-white/20 mx-1" />
                <button
                  onClick={onClose}
                  aria-label="关闭"
                  className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white hover:bg-white/10 rounded cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
          ) : isVideo(file.suffix) && url ? (
            <Suspense fallback={<div className="w-10 h-10 border-3 border-white/30 border-t-white rounded-full animate-spin" />}>
              <PlyrPlayer src={url} />
            </Suspense>
          ) : isAudio(file.suffix) && url ? (
            <div className="flex flex-col items-center gap-6">
              <div className={cn('w-32 h-32 rounded-3xl flex items-center justify-center', config.bgColor)}>
                <config.icon className={cn('w-16 h-16', config.color)} />
              </div>
              <audio src={url} controls autoPlay className="w-96" />
            </div>
          ) : isText(file.suffix) && textContent !== null ? (
            <div className="w-[80vw] h-[80vh] bg-surface rounded-lg overflow-auto">
              <pre className="p-6 text-sm text-fg font-mono whitespace-pre-wrap break-all leading-relaxed">
                {textContent}
              </pre>
            </div>
          ) : null}
        </div>

        {previewFiles.length > 1 && (
          <button
            onClick={goNext} aria-label="下一个"
            disabled={previewPos < 0 || previewPos >= previewFiles.length - 1}
            className="absolute right-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
          >
            <ChevronRight className="w-6 h-6" aria-hidden />
          </button>
        )}
      </div>

      {/* Footer */}
      {files.length > 1 && (
        <div className="text-center py-2 text-white/40 text-xs">
          {index + 1} / {files.length}
          <span className="ml-3 text-white/30">← → 切换 · Esc 关闭</span>
        </div>
      )}
    </div>
  );
}
