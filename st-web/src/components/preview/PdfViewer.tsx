import { useCallback, useEffect, useRef, useState } from 'react';
import * as pdfjsLib from 'pdfjs-dist';
import type { PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist';
import { ChevronLeft, ChevronRight, Maximize2, ZoomIn, ZoomOut } from 'lucide-react';
import { cn } from '../../lib/utils';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

// worker 作为独立资源打包；worker 加载失败时 pdf.js 会回退主线程渲染
pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl;

const PAGE_GAP = 16; // 页面间距
const LEADING_PADDING = 24; // 顶部内边距
const THUMB_WIDTH = 112; // 缩略图宽度
const MAX_THUMB_PAGES = 100; // 缩略图渲染上限
const RENDER_BUFFER = 900; // 视口外预渲染缓冲（像素）
const MAX_DPR = 2; // 主画布最大像素比（可视区虚拟化后内存可控；过低会导致高分屏文字发糊）

interface PdfViewerProps {
  blob: Blob;
  fileName: string;
}

/** 单页主画布：按传入 scale 渲染到 canvas（HiDPI 适配） */
function PageCanvas({ page, scale }: { page: PDFPageProxy; scale: number }) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    let cancelled = false;
    const dpr = Math.min(window.devicePixelRatio || 1, MAX_DPR);
    const viewport = page.getViewport({ scale });
    canvas.width = Math.floor(viewport.width * dpr);
    canvas.height = Math.floor(viewport.height * dpr);
    canvas.style.width = `${Math.floor(viewport.width)}px`;
    canvas.style.height = `${Math.floor(viewport.height)}px`;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const task = page.render({
      canvasContext: ctx,
      viewport,
      transform: [dpr, 0, 0, dpr, 0, 0],
    });
    task.promise.catch(() => {
      // cancel 触发的异常忽略
      if (!cancelled) console.error('pdf page render failed');
    });
    return () => {
      cancelled = true;
      task.cancel();
    };
  }, [page, scale]);

  return <canvas ref={ref} className="bg-white shadow-lg" />;
}

/** 左侧缩略图：小尺寸渲染单页 */
function PageThumb({
  page,
  width,
  active,
  label,
  onClick,
}: {
  page: PDFPageProxy;
  width: number;
  active: boolean;
  label: string;
  onClick: () => void;
}) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const viewport = page.getViewport({ scale: width / page.getViewport({ scale: 1 }).width });
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise.catch(() => {
      // 缩略图渲染失败可忽略，仅影响预览观感
    });
  }, [page, width]);

  return (
    <button
      data-thumb={label}
      onClick={onClick}
      className={cn(
        'w-full rounded-md overflow-hidden border transition-colors cursor-pointer',
        active ? 'border-primary-400' : 'border-transparent hover:border-border',
      )}
      aria-label={`跳转到第 ${label} 页`}
    >
      <canvas ref={ref} className="block w-full" />
      <span className="block text-center text-[11px] text-muted py-0.5">{label}</span>
    </button>
  );
}

/** 基于 pdf.js 的 PDF 预览：连续滚动 + 左侧缩略图 + 适应宽度 + 缩放 */
export default function PdfViewer({ blob, fileName }: PdfViewerProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const thumbListRef = useRef<HTMLDivElement>(null);
  const docRef = useRef<PDFDocumentProxy | null>(null);
  const pageTopsRef = useRef<number[]>([]);
  const pageHeightsRef = useRef<number[]>([]);
  const [pages, setPages] = useState<PDFPageProxy[]>([]);
  const [fitScale, setFitScale] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [pageNum, setPageNum] = useState(1);
  const [visibleRange, setVisibleRange] = useState<[number, number]>([0, 0]);
  const [error, setError] = useState<string | null>(null);

  const scale = fitScale * zoom;

  const updateVisible = useCallback(() => {
    const el = scrollRef.current;
    const tops = pageTopsRef.current;
    const heights = pageHeightsRef.current;
    if (!el || tops.length === 0) return;
    const st = el.scrollTop;
    const viewH = el.clientHeight;
    let start = tops.length - 1;
    for (let i = 0; i < tops.length; i++) {
      if (tops[i] + heights[i] > st - RENDER_BUFFER) {
        start = i;
        break;
      }
    }
    let end = start;
    for (let i = start; i < tops.length; i++) {
      if (tops[i] <= st + viewH + RENDER_BUFFER) end = i;
      else break;
    }
    setVisibleRange([start, end]);
  }, []);

  // 加载 PDF 文档与页对象
  useEffect(() => {
    let cancelled = false;
    setError(null);
    blob
      .arrayBuffer()
      .then((buf) => pdfjsLib.getDocument({ data: buf, isEvalSupported: false }).promise)
      .then(async (pdf) => {
        if (cancelled) {
          pdf.destroy();
          return;
        }
        docRef.current?.destroy();
        docRef.current = pdf;
        const arr: PDFPageProxy[] = [];
        for (let i = 1; i <= pdf.numPages; i++) {
          arr.push(await pdf.getPage(i));
        }
        if (cancelled) return;
        setPages(arr);
        setPageNum(1);
      })
      .catch((err) => {
        console.error('pdf load failed:', fileName, err);
        if (!cancelled) setError('PDF 解析失败，请确认文件完整后重试');
      });
    return () => {
      cancelled = true;
    };
  }, [blob, fileName]);

  // 卸载时释放文档
  useEffect(() => {
    return () => {
      docRef.current?.destroy();
      docRef.current = null;
    };
  }, []);

  // 适应宽度：跟随容器宽度变化
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || pages.length === 0) return;
    const compute = () => {
      const avail = el.clientWidth - 48;
      const w1 = pages[0].getViewport({ scale: 1 }).width;
      setFitScale((prev) => {
        const next = avail > 0 ? avail / w1 : 1;
        return Math.abs(prev - next) < 0.001 ? prev : next;
      });
    };
    compute();
    const ro = new ResizeObserver(compute);
    ro.observe(el);
    return () => ro.disconnect();
  }, [pages]);

  // 页面顶部偏移/高度 + 初始可视范围
  useEffect(() => {
    if (pages.length === 0) return;
    const tops: number[] = [];
    const heights: number[] = [];
    let acc = LEADING_PADDING;
    for (const p of pages) {
      tops.push(acc);
      const h = p.getViewport({ scale }).height;
      heights.push(h);
      acc += h + PAGE_GAP;
    }
    pageTopsRef.current = tops;
    pageHeightsRef.current = heights;
    updateVisible();
  }, [pages, scale, updateVisible]);

  // 滚动：更新可视范围 + 当前页
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onScroll = () => {
      updateVisible();
      const tops = pageTopsRef.current;
      const mid = el.scrollTop + el.clientHeight * 0.3;
      let idx = 0;
      for (let i = 0; i < tops.length; i++) {
        if (tops[i] <= mid) idx = i;
        else break;
      }
      setPageNum((p) => (p === idx + 1 ? p : idx + 1));
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [pages.length, updateVisible]);

  // 当前页变化时，缩略图滚动到可见位置
  useEffect(() => {
    const el = thumbListRef.current?.querySelector(`[data-thumb="${pageNum}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  }, [pageNum]);

  const jumpTo = (n: number) => {
    setPageNum(n);
    const el = scrollRef.current;
    const top = pageTopsRef.current[n - 1];
    if (el && top != null) {
      el.scrollTo({ top: Math.max(0, top - 12), behavior: 'smooth' });
    }
  };

  if (error) {
    return (
      <div className="w-[80vw] h-[80vh] bg-surface rounded-lg flex items-center justify-center">
        <div className="text-center px-6">
          <p className="text-muted text-sm mb-2">{error}</p>
          <p className="text-muted/60 text-xs">{fileName}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-[80vw] h-[80vh] bg-surface rounded-lg flex overflow-hidden">
      {/* 左侧缩略图栏 */}
      <div
        ref={thumbListRef}
        className="w-36 shrink-0 overflow-y-auto border-r border-border/60 bg-surface-2/50 p-2 space-y-2"
      >
        {pages.length === 0 ? (
          <div className="w-8 h-8 border-3 border-white/30 border-t-white rounded-full animate-spin mx-auto mt-8" />
        ) : (
          <>
            {pages.slice(0, MAX_THUMB_PAGES).map((p, i) => (
              <PageThumb
                key={i}
                page={p}
                width={THUMB_WIDTH}
                active={pageNum === i + 1}
                label={String(i + 1)}
                onClick={() => jumpTo(i + 1)}
              />
            ))}
            {pages.length > MAX_THUMB_PAGES && (
              <div className="text-center text-[11px] text-muted py-2">
                仅显示前 {MAX_THUMB_PAGES} 页缩略图
              </div>
            )}
          </>
        )}
      </div>

      {/* 右侧：连续滚动区域 + 工具栏 */}
      <div className="flex-1 flex flex-col overflow-hidden">
        <div ref={scrollRef} className="flex-1 overflow-auto bg-neutral-900/60">
          <div className="pt-6 pb-6">
            {pages.slice(visibleRange[0], visibleRange[1] + 1).map((p, idx) => {
              const i = visibleRange[0] + idx;
              const viewport = p.getViewport({ scale });
              return (
                <div
                  key={i}
                  className="mx-auto"
                  style={{
                    width: Math.floor(viewport.width),
                    height: Math.floor(viewport.height),
                    marginBottom: i === pages.length - 1 ? 0 : PAGE_GAP,
                  }}
                >
                  <PageCanvas page={p} scale={scale} />
                </div>
              );
            })}
            {pages.length === 0 && (
              <div className="w-10 h-10 border-3 border-white/30 border-t-white rounded-full animate-spin mx-auto mt-24" />
            )}
          </div>
        </div>

        {/* 底部工具栏 */}
        <div className="flex items-center justify-center gap-2 py-2.5 border-t border-border/60 bg-surface">
          <button
            onClick={() => jumpTo(Math.max(1, pageNum - 1))}
            disabled={pageNum <= 1}
            aria-label="上一页"
            className="w-8 h-8 flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 rounded cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="text-xs text-muted tabular-nums min-w-16 text-center">
            {pageNum} / {pages.length || '…'}
          </span>
          <button
            onClick={() => jumpTo(Math.min(pages.length, pageNum + 1))}
            disabled={pageNum >= pages.length}
            aria-label="下一页"
            className="w-8 h-8 flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 rounded cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
          <div className="w-px h-5 bg-border mx-2" />
          <button
            onClick={() => setZoom((z) => Math.max(0.4, Math.round((z / 1.25) * 10) / 10))}
            aria-label="缩小"
            className="w-8 h-8 flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 rounded cursor-pointer transition-colors"
          >
            <ZoomOut className="w-4 h-4" />
          </button>
          <span className="text-xs text-muted tabular-nums w-12 text-center">
            {Math.round(zoom * 100)}%
          </span>
          <button
            onClick={() => setZoom((z) => Math.min(4, Math.round((z * 1.25) * 10) / 10))}
            aria-label="放大"
            className="w-8 h-8 flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 rounded cursor-pointer transition-colors"
          >
            <ZoomIn className="w-4 h-4" />
          </button>
          <button
            onClick={() => setZoom(1)}
            aria-label="适应宽度"
            className="w-8 h-8 flex items-center justify-center text-muted hover:text-fg hover:bg-surface-2 rounded cursor-pointer transition-colors"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );
}
