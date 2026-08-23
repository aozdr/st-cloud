import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';

/** 每页条数选项（默认 100） */
const PAGE_SIZE_OPTIONS = [50, 100, 150, 200];

export interface FileBrowserPaginationProps {
  total: number;
  pageSize: number;
  page: number;
  totalPages: number;
  pageInput: string;
  setPageInput: (v: string) => void;
  onPageInputCommit: () => void;
  onPageSizeChange: (v: number) => void;
  onPrev: () => void;
  onNext: () => void;
}

export default function FileBrowserPagination({
  total, pageSize, page, totalPages, pageInput, setPageInput,
  onPageInputCommit, onPageSizeChange, onPrev, onNext,
}: FileBrowserPaginationProps) {
  if (total <= pageSize) return null;

  return (
    <div className="flex items-center justify-between gap-3 px-5 md:px-8 py-3">
      <span className="text-xs text-muted tabular-nums">共 {total} 项</span>
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-1.5 text-xs text-muted whitespace-nowrap">
          每页
          <Select value={String(pageSize)} onValueChange={(v) => onPageSizeChange(Number(v))}>
            <SelectTrigger className="h-7 w-[72px] gap-1 text-xs border-border px-2 py-0.5 font-medium text-fg">
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="min-w-[4.5rem]">
              {PAGE_SIZE_OPTIONS.map((s) => (
                <SelectItem key={s} value={String(s)}>{s}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          项
        </label>
        <div className="w-px h-4 bg-border" />
        <div className="flex items-center gap-1 text-xs text-muted">
          <span>第</span>
          <input
            value={pageInput}
            onChange={(e) => setPageInput(e.target.value.replace(/[^0-9]/g, ''))}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                onPageInputCommit();
              }
            }}
            onBlur={onPageInputCommit}
            inputMode="numeric"
            aria-label="跳转到页码"
            className="w-10 h-7 text-center text-xs text-fg bg-surface-2 rounded-md border border-border focus:border-primary-400 focus:ring-2 focus:ring-primary-100 outline-none tabular-nums"
          />
          <span className="tabular-nums">/ {totalPages} 页</span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={onPrev}
            disabled={page <= 1}
            aria-label="上一页"
            className="inline-flex items-center px-2.5 py-1.5 text-xs font-medium text-muted rounded-md border border-border bg-surface hover:bg-surface-2 hover:text-fg disabled:opacity-40 disabled:cursor-not-allowed transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            上一页
          </button>
          <button
            onClick={onNext}
            disabled={page >= totalPages}
            aria-label="下一页"
            className="inline-flex items-center px-2.5 py-1.5 text-xs font-medium text-muted rounded-md border border-border bg-surface hover:bg-surface-2 hover:text-fg disabled:opacity-40 disabled:cursor-not-allowed transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  );
}
