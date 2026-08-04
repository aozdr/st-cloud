import { useEffect, useState, useRef } from 'react';
import { X, Download, ChevronLeft, ChevronRight, FileText, Table, ChevronDown } from 'lucide-react';
import Plyr from 'plyr';
import 'plyr/dist/plyr.css';
import api from '../../lib/api';
import type { FileNode } from '../../types';
import { isImage, isVideo, isPdf, isAudio, isText, isWord, isExcel, getFileTypeConfig, cn } from '../../lib/utils';
import { renderAsync } from 'docx-preview';
import * as XLSX from 'xlsx';

function PlyrPlayer({ src }: { src: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const playerRef = useRef<Plyr | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const video = document.createElement('video');
    video.src = src;
    video.controls = true;
    video.playsInline = true;
    video.style.maxWidth = '100%';
    video.style.maxHeight = '80vh';
    container.appendChild(video);

    playerRef.current = new Plyr(video, {
      autoplay: true,
      controls: ['play-large', 'play', 'progress', 'current-time', 'duration', 'mute', 'volume', 'settings', 'pip', 'airplay', 'fullscreen'],
      settings: ['speed'],
      speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
      keyboard: { focused: true, global: true },
      tooltips: { controls: true, seek: true },
      seekTime: 10,
    });

    return () => {
      playerRef.current?.destroy();
      playerRef.current = null;
      container.innerHTML = '';
    };
  }, [src]);

  return (
    <div
      ref={containerRef}
      style={{ width: '80vw', maxWidth: '1280px', '--plyr-color-main': '#D9272E', '--plyr-video-background': '#000' } as React.CSSProperties}
      className="rounded-lg bg-black"
    />
  );
}

interface Props {
  files: FileNode[];
  currentIndex: number;
  onClose: () => void;
  shareContext?: { shareCode: string; password?: string };
  onDownload?: (file: FileNode) => void;
}

export default function PreviewModal({ files, currentIndex, onClose, shareContext, onDownload }: Props) {
  const [index, setIndex] = useState(currentIndex);
  const [url, setUrl] = useState<string | null>(null);
  const [textContent, setTextContent] = useState<string | null>(null);
  const [excelHtml, setExcelHtml] = useState<string | null>(null);
  const [excelSheets, setExcelSheets] = useState<string[]>([]);
  const [excelSheetIdx, setExcelSheetIdx] = useState(0);
  const [sheetDropdownOpen, setSheetDropdownOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const docxContainerRef = useRef<HTMLDivElement>(null);
  const excelWorkbookRef = useRef<XLSX.WorkBook | null>(null);

  const file = files[index];

  useEffect(() => {
    if (!file || file.nodeType !== 1) return;
    setLoading(true);
    setUrl(null);
    setTextContent(null);
    setExcelHtml(null);
    setExcelSheets([]);
    setExcelSheetIdx(0);
    excelWorkbookRef.current = null;

    if (shareContext) {
      // 分享模式：使用 stream 端点，无需登录 token，不会增加下载次数
      const params = new URLSearchParams({ nodeId: String(file.id) });
      if (shareContext.password) params.set('password', shareContext.password);
      const streamUrl = `/api/share/access/stream/${shareContext.shareCode}?${params}`;
      setUrl(streamUrl);

      if (isText(file.suffix)) {
        fetch(streamUrl)
          .then((res) => res.text())
          .then((text) => {
            setTextContent(text.length > 500000 ? text.slice(0, 500000) + '\n\n... (内容过长，已截断)' : text);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else if (isWord(file.suffix)) {
        fetch(streamUrl)
          .then((res) => res.blob())
          .then((blob) => {
            if (docxContainerRef.current) {
              renderAsync(blob, docxContainerRef.current, undefined, {
                className: 'docx-container',
                inWrapper: true,
                ignoreWidth: false,
                ignoreHeight: false,
              }).then(() => setLoading(false));
            }
          })
          .catch(() => setLoading(false));
      } else if (isExcel(file.suffix)) {
        fetch(streamUrl)
          .then((res) => res.blob())
          .then((blob) => blob.arrayBuffer())
          .then((data) => {
            const wb = XLSX.read(data);
            excelWorkbookRef.current = wb;
            setExcelSheets(wb.SheetNames);
            setExcelSheetIdx(0);
            const html = XLSX.utils.sheet_to_html(wb.Sheets[wb.SheetNames[0]], { editable: false });
            setExcelHtml(html);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else {
        setLoading(false);
      }
    } else {
      // 正常模式：需登录 token
      const token = localStorage.getItem('accessToken');
      // 预览媒体直接走服务端限速流（inline），不再使用预签名直链
      setUrl(`/api/file/${file.id}/stream?token=${encodeURIComponent(token || '')}&inline=1`);

      const streamUrl = `/api/file/${file.id}/stream`;
      const headers = { Authorization: `Bearer ${token}` };

      if (isText(file.suffix)) {
        fetch(streamUrl, { headers })
          .then((res) => res.text())
          .then((text) => {
            setTextContent(text.length > 500000 ? text.slice(0, 500000) + '\n\n... (内容过长，已截断)' : text);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else if (isWord(file.suffix)) {
        fetch(streamUrl, { headers })
          .then((res) => res.blob())
          .then((blob) => {
            if (docxContainerRef.current) {
              renderAsync(blob, docxContainerRef.current, undefined, {
                className: 'docx-container',
                inWrapper: true,
                ignoreWidth: false,
                ignoreHeight: false,
              }).then(() => setLoading(false));
            }
          })
          .catch(() => setLoading(false));
      } else if (isExcel(file.suffix)) {
        fetch(streamUrl, { headers })
          .then((res) => res.blob())
          .then((blob) => blob.arrayBuffer())
          .then((data) => {
            const wb = XLSX.read(data);
            excelWorkbookRef.current = wb;
            setExcelSheets(wb.SheetNames);
            setExcelSheetIdx(0);
            const html = XLSX.utils.sheet_to_html(wb.Sheets[wb.SheetNames[0]], { editable: false });
            setExcelHtml(html);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      } else {
        setLoading(false);
      }
    }
  }, [file, shareContext?.shareCode, shareContext?.password]);

  // Switch Excel sheet
  useEffect(() => {
    if (excelWorkbookRef.current && excelSheets.length > 0) {
      const html = XLSX.utils.sheet_to_html(excelWorkbookRef.current.Sheets[excelSheets[excelSheetIdx]], { editable: false });
      setExcelHtml(html);
    }
  }, [excelSheetIdx]);

  // Keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft') setIndex((i) => Math.max(0, i - 1));
      else if (e.key === 'ArrowRight') setIndex((i) => Math.min(files.length - 1, i + 1));
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [files.length, onClose]);

  if (!file) return null;

  const config = getFileTypeConfig(file.nodeType, file.suffix);
  const canPreview = isImage(file.suffix) || isVideo(file.suffix) || isPdf(file.suffix) || isAudio(file.suffix) || isText(file.suffix) || isWord(file.suffix) || isExcel(file.suffix);

  const goPrev = () => setIndex((i) => Math.max(0, i - 1));
  const goNext = () => setIndex((i) => Math.min(files.length - 1, i + 1));

  const switchSheet = (idx: number) => {
    setExcelSheetIdx(idx);
    setSheetDropdownOpen(false);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-stone-900/95 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-3 bg-stone-900/80">
        <div className="flex items-center gap-3 min-w-0">
          <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0', config.bgColor)}>
            <config.icon className={cn('w-4 h-4', config.color)} />
          </div>
          <span className="text-white text-sm font-medium truncate">{file.name}</span>
          {/* Excel sheet selector */}
          {isExcel(file.suffix) && excelSheets.length > 1 && (
            <div className="relative ml-2">
              <button
                onClick={() => setSheetDropdownOpen(!sheetDropdownOpen)}
                className="flex items-center gap-1 px-2 py-1 text-xs text-white/70 hover:text-white hover:bg-white/10 rounded transition-colors"
              >
                <Table className="w-3 h-3" />
                {excelSheets[excelSheetIdx]}
                <ChevronDown className="w-3 h-3" />
              </button>
              {sheetDropdownOpen && (
                <div className="absolute top-full left-0 mt-1 bg-white rounded-lg shadow-md border border-stone-200 py-1 min-w-[120px] z-20">
                  {excelSheets.map((name, idx) => (
                    <button
                      key={idx}
                      onClick={() => switchSheet(idx)}
                      className={cn(
                        'w-full text-left px-3 py-1.5 text-xs cursor-pointer transition-colors',
                        idx === excelSheetIdx ? 'bg-primary-50 text-primary-700' : 'text-stone-700 hover:bg-stone-50'
                      )}
                    >
                      {name}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
        <div className="flex items-center gap-2">
          {url && onDownload ? (
            <button
              onClick={() => onDownload(file)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white/80 hover:text-white hover:bg-white/10 rounded-lg cursor-pointer transition-colors"
            >
              <Download className="w-4 h-4" />
              <span>下载</span>
            </button>
          ) : url ? (
            <a
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white/80 hover:text-white hover:bg-white/10 rounded-lg cursor-pointer transition-colors"
            >
              <Download className="w-4 h-4" />
              <span>下载</span>
            </a>
          ) : null}
          <button onClick={onClose} className="text-white/60 hover:text-white p-1.5 cursor-pointer transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 flex items-center justify-center relative overflow-hidden">
        {files.length > 1 && (
          <button
            onClick={goPrev}
            disabled={index === 0}
            className="absolute left-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10"
          >
            <ChevronLeft className="w-6 h-6" />
          </button>
        )}

        <div className="max-w-[90%] max-h-[90%] flex items-center justify-center">
          {loading ? (
            <div className="w-10 h-10 border-3 border-white/30 border-t-white rounded-full animate-spin" />
          ) : !canPreview ? (
            <div className="text-center">
              <div className={cn('w-24 h-24 rounded-2xl flex items-center justify-center mx-auto mb-4', config.bgColor)}>
                <config.icon className={cn('w-10 h-10', config.color)} />
              </div>
              <p className="text-white/60 text-sm mb-3">此文件类型不支持在线预览</p>
              {url && onDownload ? (
                <button
                  onClick={() => onDownload(file)}
                  className="inline-flex items-center gap-2 px-4 py-2 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg cursor-pointer transition-colors"
                >
                  <Download className="w-4 h-4" />
                  <span>下载文件</span>
                </button>
              ) : url ? (
                <a
                  href={url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 px-4 py-2 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg cursor-pointer transition-colors"
                >
                  <Download className="w-4 h-4" />
                  <span>下载文件</span>
                </a>
              ) : null}
            </div>
          ) : isImage(file.suffix) && url ? (
            <img src={url} alt={file.name} className="max-w-full max-h-full object-contain rounded-lg" />
          ) : isVideo(file.suffix) && url ? (
            <PlyrPlayer src={url} />
          ) : isAudio(file.suffix) && url ? (
            <div className="flex flex-col items-center gap-6">
              <div className={cn('w-32 h-32 rounded-3xl flex items-center justify-center', config.bgColor)}>
                <config.icon className={cn('w-16 h-16', config.color)} />
              </div>
              <audio src={url} controls autoPlay className="w-96" />
            </div>
          ) : isPdf(file.suffix) && url ? (
            <iframe src={url} className="w-[80vw] h-[80vh] bg-white rounded-lg" title={file.name} />
          ) : isText(file.suffix) && textContent !== null ? (
            <div className="w-[80vw] h-[80vh] bg-white rounded-lg overflow-auto">
              <pre className="p-6 text-sm text-stone-800 font-mono whitespace-pre-wrap break-all leading-relaxed">
                {textContent}
              </pre>
            </div>
          ) : isWord(file.suffix) ? (
            <div className="w-[80vw] h-[80vh] bg-white rounded-lg overflow-hidden shadow-lg">
              <div ref={docxContainerRef} className="docx-container" />
            </div>
          ) : isExcel(file.suffix) && excelHtml ? (
            <div className="w-[80vw] h-[80vh] bg-white rounded-lg overflow-hidden shadow-lg">
              <style>{`
                .xlsx-table { border-collapse: collapse; width: 100%; font-size: 13px; }
                .xlsx-table td, .xlsx-table th { border: 1px solid #e7e5e4; padding: 4px 8px; text-align: left; white-space: nowrap; }
                .xlsx-table tr:first-child td { background: #f5f5f4; font-weight: 600; }
              `}</style>
              <table className="xlsx-table" dangerouslySetInnerHTML={{ __html: excelHtml }} />
            </div>
          ) : null}
        </div>

        {files.length > 1 && (
          <button
            onClick={goNext}
            disabled={index === files.length - 1}
            className="absolute right-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10"
          >
            <ChevronRight className="w-6 h-6" />
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
