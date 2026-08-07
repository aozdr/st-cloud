import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { X, Download, ChevronLeft, ChevronRight, Table, ChevronDown } from 'lucide-react';
import api from '../../lib/api';
import type { FileNode, PreviewResult } from '../../types';
import { addRecentFile } from '../../lib/recentFiles';
import { isImage, isVideo, isPdf, isAudio, isText, isWord, isExcel, getFileTypeConfig, cn } from '../../lib/utils';
import type { WorkBook } from 'xlsx';

const PlyrPlayer = lazy(() => import('./PlyrPlayer'));

async function renderDocx(blob: Blob, container: HTMLElement) {
  const { renderAsync } = await import('docx-preview');
  container.innerHTML = '';
  await renderAsync(blob, container, undefined, {
    className: 'docx-container',
    inWrapper: true,
    ignoreWidth: false,
    ignoreHeight: false,
    breakPages: true,
    experimental: true,
  });
}

async function parseExcel(data: ArrayBuffer): Promise<{ sheets: string[]; firstHtml: string; wb: WorkBook }> {
  const XLSX = await import('xlsx');
  const wb = XLSX.read(data);
  return {
    sheets: wb.SheetNames,
    firstHtml: XLSX.utils.sheet_to_html(wb.Sheets[wb.SheetNames[0]], { editable: false }),
    wb,
  };
}

async function excelSheetToHtml(wb: WorkBook, sheetName: string): Promise<string> {
  const XLSX = await import('xlsx');
  return XLSX.utils.sheet_to_html(wb.Sheets[sheetName], { editable: false });
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
  const [docxBlob, setDocxBlob] = useState<Blob | null>(null);
  const docxContainerRef = useRef<HTMLDivElement>(null);
  const excelWorkbookRef = useRef<WorkBook | null>(null);

  // Separate effect: render docx blob into container when both are ready
  useEffect(() => {
    if (!docxBlob || !docxContainerRef.current) return;
    let cancelled = false;
    renderDocx(docxBlob, docxContainerRef.current)
      .then(() => { if (!cancelled) setLoading(false); })
      .catch((err) => { if (!cancelled) { console.error('docx render failed:', err); setLoading(false); } });
    return () => { cancelled = true; };
  }, [docxBlob]);
  const file = files[index];

  useEffect(() => {
    if (file && file.nodeType === 1) addRecentFile(file);
  }, [file]);

  useEffect(() => {
    if (!file || file.nodeType !== 1) return;
    setLoading(true);
    setUrl(null);
    setTextContent(null);
    setExcelHtml(null);
    setExcelSheets([]);
    setExcelSheetIdx(0);
    excelWorkbookRef.current = null;
    setDocxBlob(null);

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
          .then((blob) => { setDocxBlob(blob); setLoading(false); })
          .catch(() => setLoading(false));
      } else if (isExcel(file.suffix)) {
        fetch(streamUrl)
          .then((res) => res.blob())
          .then((blob) => blob.arrayBuffer())
          .then((data) => {
            parseExcel(data).then(({ sheets, firstHtml, wb }) => {
              excelWorkbookRef.current = wb;
              setExcelSheets(sheets);
              setExcelSheetIdx(0);
              setExcelHtml(firstHtml);
              setLoading(false);
            });
          })
          .catch(() => setLoading(false));
      } else {
        setLoading(false);
      }
    } else {
      // 正常模式：需登录 token
      const token = localStorage.getItem('accessToken');
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
          .then((res) => { if (!res.ok) throw new Error('HTTP ' + res.status); return res.blob(); })
          .then((blob) => { setDocxBlob(blob); setLoading(false); })
          .catch((err) => { console.error('docx fetch failed:', err); setLoading(false); });
      } else if (isExcel(file.suffix)) {
        fetch(streamUrl, { headers })
          .then((res) => res.blob())
          .then((blob) => blob.arrayBuffer())
          .then((data) => {
            parseExcel(data).then(({ sheets, firstHtml, wb }) => {
              excelWorkbookRef.current = wb;
              setExcelSheets(sheets);
              setExcelSheetIdx(0);
              setExcelHtml(firstHtml);
              setLoading(false);
            });
          })
          .catch(() => setLoading(false));
      } else if (isImage(file.suffix)) {
        // 图片：通过预览 API 获取预签名 URL（access token 无法用于 <img src>）
        api.get<string>(`/preview/${file.id}/thumbnail`, { params: { size: 'lg' } })
          .then((u) => { setUrl(u); setLoading(false); })
          .catch(() => setLoading(false));
      } else if (isVideo(file.suffix) || isAudio(file.suffix) || isPdf(file.suffix)) {
        // 视频/音频/PDF：通过预览 API 获取预签名 URL（支持 Range 请求/拖动进度条）
        api.get<PreviewResult>(`/preview/${file.id}`)
          .then((data) => {
            if (data.url) {
              setUrl(data.url);
              setLoading(false);
            } else {
              // 后端不支持该格式预览，回退到下载令牌流
              return api.post<{ token: string }>(`/file/${file.id}/download-token`)
                .then((d) => {
                  setUrl(`/api/file/${file.id}/stream?token=${encodeURIComponent(d.token)}&inline=1`);
                  setLoading(false);
                });
            }
          })
          .catch(() => setLoading(false));
      } else {
        // 不支持预览：获取下载令牌用于下载按钮
        api.post<{ token: string }>(`/file/${file.id}/download-token`)
          .then((data) => {
            setUrl(`/api/file/${file.id}/stream?token=${encodeURIComponent(data.token)}&inline=1`);
            setLoading(false);
          })
          .catch(() => setLoading(false));
      }
    }
  }, [file, shareContext?.shareCode, shareContext?.password]);

  // Switch Excel sheet
  useEffect(() => {
    if (excelWorkbookRef.current && excelSheets.length > 0) {
      excelSheetToHtml(excelWorkbookRef.current, excelSheets[excelSheetIdx]).then(setExcelHtml);
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
            <config.icon className={cn('w-4 h-4', config.color)} aria-hidden />
          </div>
          <span className="text-white text-sm font-medium truncate">{file.name}</span>
          {/* Excel sheet selector */}
          {isExcel(file.suffix) && excelSheets.length > 1 && (
            <div className="relative ml-2">
              <button
                onClick={() => setSheetDropdownOpen(!sheetDropdownOpen)}
                className="flex items-center gap-1 px-2 py-1 text-xs text-white/70 hover:text-white hover:bg-white/10 rounded transition-colors"
              >
                <Table className="w-3 h-3" aria-hidden />
                {excelSheets[excelSheetIdx]}
                <ChevronDown className="w-3 h-3" aria-hidden />
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
          <button onClick={onClose} aria-label="关闭" className="text-white/60 hover:text-white p-1.5 cursor-pointer transition-colors">
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 flex items-center justify-center relative overflow-hidden">
        {files.length > 1 && (
          <button
            onClick={goPrev} aria-label="上一个"
            disabled={index === 0}
            className="absolute left-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10"
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
            <img src={url} alt={file.name} className="max-w-full max-h-full object-contain rounded-lg" />
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
          ) : isPdf(file.suffix) && url ? (
            <iframe src={url} className="w-[80vw] h-[80vh] bg-white rounded-lg" title={file.name} />
          ) : isText(file.suffix) && textContent !== null ? (
            <div className="w-[80vw] h-[80vh] bg-white rounded-lg overflow-auto">
              <pre className="p-6 text-sm text-stone-800 font-mono whitespace-pre-wrap break-all leading-relaxed">
                {textContent}
              </pre>
            </div>
          ) : isWord(file.suffix) ? (
            <div className="w-[80vw] h-[80vh] bg-white rounded-lg overflow-auto shadow-lg">
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
            onClick={goNext} aria-label="下一个"
            disabled={index === files.length - 1}
            className="absolute right-4 p-2 text-white/60 hover:text-white hover:bg-white/10 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed z-10"
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
