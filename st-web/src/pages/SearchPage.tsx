import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Search, ChevronRight, FolderOpen, SlidersHorizontal, FileText, Image as ImageIcon, Video, Music, Archive, ArrowDownUp, Calendar, HardDrive, RotateCcw, X, Users, Loader2, AlertTriangle } from 'lucide-react';
import { format } from 'date-fns';
import { Calendar as CalendarPicker } from '../components/ui/calendar';
import { Popover, PopoverContent, PopoverTrigger } from '../components/ui/popover';
import api from '../lib/api';
import type { SearchResultVO, SearchResultPage, TeamSearchResultPage, TeamSearchResultVO, TeamSpace, FileNode, PageResult } from '../types';
import { getFileTypeConfig, formatSize, formatDate, cn, sanitizeHighlight } from '../lib/utils';
import { type FileTypeFilter, FILTER_SUFFIXES } from '../lib/fileTypes';
import FileTypeIcon from '../components/file/FileTypeIcon';
import PreviewModal from '../components/preview/PreviewModal';
import { useMobile } from '../hooks/useMobile';
import { teamFileSource } from '../lib/fileSource';

type SortOption = 'relevance' | 'name' | 'size_desc' | 'size_asc' | 'date_desc' | 'date_asc';
type SizeFilter = 'all' | 'small' | 'medium' | 'large';
type DateRange = { from: Date; to: Date };


const FILE_TYPE_TABS: { value: FileTypeFilter; label: string; icon: typeof FileText }[] = [
  { value: 'all', label: '全部', icon: Search },
  { value: 'folder', label: '文件夹', icon: FolderOpen },
  { value: 'image', label: '图片', icon: ImageIcon },
  { value: 'video', label: '视频', icon: Video },
  { value: 'audio', label: '音频', icon: Music },
  { value: 'document', label: '文档', icon: FileText },
  { value: 'archive', label: '压缩包', icon: Archive },
];

const SORT_OPTIONS: { value: SortOption; label: string }[] = [
  { value: 'relevance', label: '相关度优先' },
  { value: 'name', label: '名称 A-Z' },
  { value: 'size_desc', label: '大小从大到小' },
  { value: 'size_asc', label: '大小从小到大' },
  { value: 'date_desc', label: '修改时间最新' },
  { value: 'date_asc', label: '修改时间最早' },
];

const SIZE_OPTIONS: { value: SizeFilter; label: string }[] = [
  { value: 'all', label: '全部大小' },
  { value: 'small', label: '小于 1 MB' },
  { value: 'medium', label: '1 - 100 MB' },
  { value: 'large', label: '大于 100 MB' },
];

const MB = 1024 * 1024;
const DAY = 24 * 60 * 60 * 1000;

function getSizeParams(size: SizeFilter): { min?: number; max?: number } {
  switch (size) {
    case 'small': return { max: MB };
    case 'medium': return { min: MB, max: 100 * MB };
    case 'large': return { min: 100 * MB };
    default: return {};
  }
}

function startOfDay(d: Date): Date { const r = new Date(d); r.setHours(0, 0, 0, 0); return r; }
function endOfDay(d: Date): Date { const r = new Date(d); r.setHours(23, 59, 59, 999); return r; }
function makeRange(daysAgo: number): DateRange { const to = startOfDay(new Date()); return { from: new Date(to.getTime() - daysAgo * DAY), to }; }

const QUICK_PRESETS = [
  { label: '今天', getRange: () => makeRange(0) },
  { label: '近 7 天', getRange: () => makeRange(6) },
  { label: '近 30 天', getRange: () => makeRange(29) },
  { label: '近一年', getRange: () => makeRange(364) },
];

/* ---------- Reusable dropdown (click-outside, no Radix) ---------- */
function FilterDropdown({ icon: Icon, label, options, value, onChange }: {
  icon: typeof Calendar; label: string;
  options: { value: string; label: string }[]; value: string; onChange: (v: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const active = value !== options[0].value;
  const selectedLabel = options.find((o) => o.value === value)?.label || label;

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen(!open)} className={cn('flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer border', active ? 'bg-primary-500/10 text-primary-600 border-primary-200' : 'bg-surface text-muted border-border hover:bg-surface-2 hover:border-border')}>
        <Icon className="w-3.5 h-3.5" aria-hidden /><span>{selectedLabel}</span>
      </button>
      {open && (
        <div className="absolute top-full left-0 mt-1 w-44 bg-surface rounded-lg border border-border shadow-lg py-1 z-50 animate-scale-in">
          {options.map((opt) => (
            <button key={opt.value} onClick={() => { onChange(opt.value); setOpen(false); }} className={cn('w-full text-left px-3 py-1.5 text-xs transition-colors cursor-pointer', opt.value === value ? 'text-primary-600 bg-primary-500/10 font-medium' : 'text-muted hover:bg-surface-2')}>{opt.label}</button>
          ))}
        </div>
      )}
    </div>
  );
}

/* ---------- Date range picker (Radix Popover, portal-rendered) ---------- */
function DateRangeFilter({ value, onChange }: { value: DateRange | undefined; onChange: (r: DateRange | undefined) => void; }) {
  const [open, setOpen] = useState(false);
  const [local, setLocal] = useState<DateRange | undefined>(value);

  useEffect(() => { if (open) setLocal(value); }, [open, value]);

  const active = !!value;
  const label = value ? `${format(value.from, 'MM-dd')}~${format(value.to, 'MM-dd')}` : '时间';

  function apply() {
    if (local?.from) onChange({ from: startOfDay(local.from), to: endOfDay(local.to || local.from) });
    else onChange(undefined);
    setOpen(false);
  }

  return (
    <div className="flex items-center gap-0.5">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button className={cn('flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer border', active ? 'bg-primary-500/10 text-primary-600 border-primary-200' : 'bg-surface text-muted border-border hover:bg-surface-2 hover:border-border')}>
            <Calendar className="w-3.5 h-3.5" aria-hidden />
            <span>{label}</span>
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start" sideOffset={4}>
          <div className="p-3">
            <div className="flex items-center gap-1.5 mb-3 flex-wrap">
              {QUICK_PRESETS.map((p) => (
                <button key={p.label} onClick={() => setLocal(p.getRange())} className="px-2 py-1 text-xs text-primary-600 hover:text-primary-600 bg-primary-500/10 hover:bg-primary-100 rounded-md cursor-pointer transition-colors">{p.label}</button>
              ))}
              <button onClick={() => setLocal(undefined)} className="px-2 py-1 text-xs text-muted hover:text-fg bg-surface-2 hover:bg-surface-2 rounded-md cursor-pointer transition-colors">全部</button>
            </div>
            <CalendarPicker mode="range" selected={local ? { from: local.from, to: local.to } : undefined} onSelect={(range: { from?: Date; to?: Date } | undefined) => { if (range?.from) setLocal({ from: range.from, to: range.to || range.from }); else setLocal(undefined); }} numberOfMonths={1} />
            <div className="flex items-center justify-between mt-2 pt-2 border-t border-border">
              <span className="text-xs text-muted">{local?.from ? `${format(local.from, 'yyyy-MM-dd')}${local?.to && local.to.getTime() !== local.from.getTime() ? ` ~ ${format(local.to, 'yyyy-MM-dd')}` : ''}` : '请选择日期范围'}</span>
              <div className="flex items-center gap-2">
                <button onClick={() => { setLocal(undefined); onChange(undefined); setOpen(false); }} className="px-2.5 py-1 text-xs text-muted hover:text-fg cursor-pointer transition-colors">清除</button>
                <button onClick={apply} className="px-3 py-1 text-xs font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md cursor-pointer transition-colors">确定</button>
              </div>
            </div>
          </div>
        </PopoverContent>
      </Popover>
      {active && (
        <button onClick={() => onChange(undefined)} className="w-5 h-5 flex items-center justify-center rounded-full hover:bg-surface-2 text-muted hover:text-fg transition-colors cursor-pointer" aria-label="清除时间筛选">
          <X className="w-3 h-3" aria-hidden />
        </button>
      )}
    </div>
  );
}
function SkeletonCard() {
  return (
    <div className="flex items-start gap-3.5 py-3 px-4">
      <div className="w-7 h-7 rounded-lg bg-surface-2 shimmer flex-shrink-0 mt-0.5" />
      <div className="flex-1 min-w-0 space-y-2">
        <div className="h-4 bg-surface-2 rounded shimmer w-2/3" />
        <div className="h-3 bg-surface-2 rounded shimmer w-1/3" />
        <div className="h-3 bg-surface-2 rounded shimmer w-1/2" />
      </div>
    </div>
  );
}

type SearchScope = 'personal' | 'team';
type SearchErrorKind = 'unavailable' | 'scope' | 'cursor' | 'generic';
type SearchErrorState = { kind: SearchErrorKind; message: string };

function isAbortError(error: unknown): boolean {
  const value = error as { code?: string; name?: string } | null;
  return value?.code === 'ERR_CANCELED' || value?.name === 'AbortError';
}

function PersonalSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const isMobile = useMobile();
  const urlKeyword = searchParams.get('keyword') || '';
  const urlNonce = searchParams.get('_t') || '';
  const urlFileType = (searchParams.get('fileType') as FileTypeFilter) || 'all';

  const [keyword, setKeyword] = useState(urlKeyword);
  const [results, setResults] = useState<SearchResultVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [searchTime, setSearchTime] = useState(0);
  const [preview, setPreview] = useState<{ files: FileNode[]; index: number } | null>(null);

  const [fileType, setFileType] = useState<FileTypeFilter>(urlFileType);
  const [sortBy, setSortBy] = useState<SortOption>('relevance');
  const [sizeFilter, setSizeFilter] = useState<SizeFilter>('all');
  const [dateRange, setDateRange] = useState<DateRange | undefined>(undefined);
  const requestSeqRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);

  const doSearch = useCallback(async (kw: string, ft: FileTypeFilter, sf: SizeFilter) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestSeq = ++requestSeqRef.current;
    if (!kw.trim()) {
      setResults([]);
      setSearched(false);
      setLoading(false);
      controllerRef.current = null;
      return;
    }
    setLoading(true);
    setSearched(true);
    const t0 = Date.now();
    try {
      const params: Record<string, unknown> = { keyword: kw, page: 1, size: 50 };
      if (ft === 'folder') params.nodeType = 0;
      else if (ft !== 'all') params.suffixes = FILTER_SUFFIXES[ft].join(',');
      const sr = getSizeParams(sf);
      if (sr.min !== undefined) params.sizeMin = sr.min;
      if (sr.max !== undefined) params.sizeMax = sr.max;
      const res = await api.get<SearchResultPage>('/search', { params, signal: controller.signal });
      if (requestSeq !== requestSeqRef.current) return;
      setResults(res?.records || []);
      setSearchTime(Date.now() - t0);
    } catch (error) {
      if (isAbortError(error) || requestSeq !== requestSeqRef.current) return;
      setResults([]);
      setSearchTime(Date.now() - t0);
    } finally {
      if (requestSeq === requestSeqRef.current) setLoading(false);
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }, []);

  useEffect(() => () => {
    requestSeqRef.current += 1;
    controllerRef.current?.abort();
  }, []);

  /* Triggered by URL changes (TopBar search, initial load, filter changes) */
  useEffect(() => {
    setKeyword(urlKeyword);
    setFileType(urlFileType);
    if (urlKeyword) {
      doSearch(urlKeyword, urlFileType, sizeFilter);
    } else {
      setResults([]); setSearched(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlKeyword, urlNonce, urlFileType, doSearch]);

  /* Re-search when server-side filters change */
  useEffect(() => {
    if (searched && keyword) doSearch(keyword, fileType, sizeFilter);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileType, sizeFilter]);

  const triggerSearch = (kw: string) => {
    const trimmed = kw.trim();
    if (!trimmed) return;
    const isSame = trimmed === urlKeyword;
    setSearchParams({ keyword: trimmed, _t: String(Date.now()) }, { replace: isSame });
  };

  const handleSearchSubmit = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') triggerSearch(keyword);
  };

  const switchToTeamSearch = () => {
    const next = new URLSearchParams(searchParams);
    next.set('scope', 'team');
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
  };

  /* Client-side date filtering */
  const dateFiltered = useMemo(() => {
    if (!dateRange) return results;
    const fromMs = dateRange.from.getTime();
    const toMs = dateRange.to.getTime();
    return results.filter((item) => {
      if (!item.updatedAt) return false;
      const ms = new Date(item.updatedAt).getTime();
      return !isNaN(ms) && ms >= fromMs && ms <= toMs;
    });
  }, [results, dateRange]);

  /* Client-side sorting */
  const sortedResults = useMemo(() => {
    if (sortBy === 'relevance') return dateFiltered;
    const arr = [...dateFiltered];
    switch (sortBy) {
      case 'name': return arr.sort((a, b) => (a.fileName || '').localeCompare(b.fileName || ''));
      case 'size_desc': return arr.sort((a, b) => Number(b.fileSize || 0) - Number(a.fileSize || 0));
      case 'size_asc': return arr.sort((a, b) => Number(a.fileSize || 0) - Number(b.fileSize || 0));
      case 'date_desc': return arr.sort((a, b) => new Date(b.updatedAt || 0).getTime() - new Date(a.updatedAt || 0).getTime());
      case 'date_asc': return arr.sort((a, b) => new Date(a.updatedAt || 0).getTime() - new Date(b.updatedAt || 0).getTime());
      default: return arr;
    }
  }, [dateFiltered, sortBy]);

  const hasActiveFilters = fileType !== 'all' || sizeFilter !== 'all' || dateRange !== undefined || sortBy !== 'relevance';
  const clearFilters = () => { setFileType('all'); setSortBy('relevance'); setSizeFilter('all'); setDateRange(undefined); };
  const getParentPath = (path: string): string => { if (!path) return ''; const idx = path.lastIndexOf('/'); return idx > 0 ? path.substring(0, idx) : ''; };

  return (
    <div className="flex flex-col h-full bg-surface-2">
      {/* Search header */}
      <div className="px-6 py-4 border-b border-border bg-surface flex-shrink-0">
        <div className="max-w-4xl mx-auto">
          <div className="relative group">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-muted group-focus-within:text-primary-600 transition-colors" aria-hidden />
            <input type="text" value={keyword} onChange={(e) => setKeyword(e.target.value)} onKeyDown={handleSearchSubmit} placeholder="搜索文件名或文档内容…" autoFocus={!isMobile} className="w-full pl-12 pr-24 py-2.5 bg-surface-2 border border-border rounded-xl text-base text-fg placeholder-muted outline-none transition focus:bg-surface focus:border-primary-400 focus:ring-2 focus:ring-primary-100" />
            <button onClick={() => triggerSearch(keyword)} className="absolute right-2 top-1/2 -translate-y-1/2 px-3 py-1 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 active:bg-primary-800 cursor-pointer transition-colors">搜索</button>
          </div>
          <div className="mt-3 flex items-center gap-2" role="group" aria-label="搜索范围">
            <span className="text-xs text-muted">搜索范围</span>
            <div className="flex items-center gap-1 rounded-lg bg-surface-2 p-0.5">
              <button type="button" className="cursor-pointer rounded-md bg-surface px-3 py-1.5 text-xs font-medium text-primary-600 shadow-sm" aria-pressed="true">个人文件</button>
              <button type="button" onClick={switchToTeamSearch} className="cursor-pointer rounded-md px-3 py-1.5 text-xs font-medium text-muted transition-colors hover:text-fg">团队文件</button>
            </div>
          </div>
          {(searched || urlKeyword) && (
            <div className="flex items-center gap-2 mt-2.5 text-sm">
              <span className="text-muted">搜索</span>
              <span className="font-medium text-fg">{urlKeyword === '*' ? '\u5168\u90e8\u6587\u4ef6' : '\u201c' + urlKeyword + '\u201d'}</span>
              {!loading && (
                <>
                  <span className="text-muted/60">&middot;</span>
                  <span className="text-muted">{dateFiltered.length} 个结果{dateRange && results.length !== dateFiltered.length && ` (共 ${results.length} 条)`}{searchTime > 0 && `\u00b7 \u8017\u65f6 ${searchTime}ms`}</span>
                  {hasActiveFilters && (
                    <button onClick={clearFilters} className="ml-2 flex items-center gap-1 text-xs text-primary-600 hover:text-primary-600 cursor-pointer"><RotateCcw className="w-3 h-3" aria-hidden /><span>清除筛选</span></button>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Filter bar */}
      {searched && (
        <div className="px-6 py-2.5 border-b border-border bg-surface flex-shrink-0 relative z-20 overflow-visible">
          <div className="max-w-4xl mx-auto flex items-center gap-2 flex-wrap">
            <div className="flex items-center gap-1 bg-surface-2 rounded-lg p-0.5">
              {FILE_TYPE_TABS.map((tab) => (
                <button key={tab.value} onClick={() => setFileType(tab.value)} className={cn('flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer', fileType === tab.value ? 'bg-surface text-primary-600 shadow-sm' : 'text-muted hover:text-fg')}>
                  <tab.icon className="w-3.5 h-3.5" aria-hidden /><span>{tab.label}</span>
                </button>
              ))}
            </div>
            <div className="w-px h-5 bg-surface-2" />
            <FilterDropdown icon={ArrowDownUp} label="排序" options={SORT_OPTIONS} value={sortBy} onChange={(v) => setSortBy(v as SortOption)} />
            <FilterDropdown icon={HardDrive} label="大小" options={SIZE_OPTIONS} value={sizeFilter} onChange={(v) => setSizeFilter(v as SizeFilter)} />
            <DateRangeFilter value={dateRange} onChange={setDateRange} />
          </div>
        </div>
      )}

      {/* Results */}
      <div className="flex-1 overflow-auto">
        <div className="max-w-4xl mx-auto px-6 py-4">
          {loading ? (
            <div className="space-y-1 rounded-xl bg-surface overflow-hidden divide-y divide-stone-50">{Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}</div>
          ) : !searched ? (
            <div className="flex flex-col items-center justify-center py-20 text-muted">
              <div className="w-16 h-16 rounded-2xl bg-surface-2 flex items-center justify-center mb-4"><Search className="w-8 h-8 text-muted/60" strokeWidth={1.2} aria-hidden /></div>
              <p className="text-base font-medium text-muted">开始你的搜索</p>
              <p className="text-sm mt-2 text-muted">在上方搜索框输入关键词，支持文件名和文档内容搜索</p>
              <div className="flex items-center gap-2 mt-6">
                {FILE_TYPE_TABS.slice(1).map((tab) => (
                  <div key={tab.value} className="flex items-center gap-1 px-2.5 py-1 bg-surface border border-border rounded-lg text-xs text-muted"><tab.icon className="w-3 h-3" aria-hidden /><span>{tab.label}</span></div>
                ))}
              </div>
            </div>
          ) : sortedResults.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-muted">
              <div className="w-16 h-16 rounded-2xl bg-surface-2 flex items-center justify-center mb-4"><SlidersHorizontal className="w-8 h-8 text-muted/60" strokeWidth={1.2} aria-hidden /></div>
              <p className="text-base font-medium text-muted">{hasActiveFilters ? '没有符合条件的文件' : '未找到相关文件'}</p>
              <p className="text-sm mt-2 text-muted">{hasActiveFilters ? '试试调整筛选条件或清除筛选' : '换个关键词试试吧'}</p>
              {hasActiveFilters && (<button onClick={clearFilters} className="mt-4 px-4 py-2 bg-surface border border-border rounded-lg text-sm text-muted hover:bg-surface-2 hover:border-border transition-colors cursor-pointer">清除所有筛选</button>)}
            </div>
          ) : (
            <div className="rounded-xl bg-surface overflow-hidden divide-y divide-stone-50">
              {sortedResults.map((item) => {
                const isFolder = item.nodeType === 0 || (item.nodeType == null && !item.suffix);
                const config = getFileTypeConfig(isFolder ? 0 : 1, item.suffix);
                const parentPath = getParentPath(item.path);
                return (
                  <div key={item.fileId} role="button" tabIndex={0} aria-label={`打开 ${item.fileName.replace(/<[^>]*>/g, "")}`} onClick={() => {
                    if (isFolder) { navigate(`/files/${item.fileId}`); return; }
                    const fileNodes: FileNode[] = sortedResults.filter(r => !(r.nodeType === 0 || (r.nodeType == null && !r.suffix))).map(r => ({ id: r.fileId, parentId: '', nodeType: 1, name: r.fileName.replace(/<[^>]*>/g, ''), path: r.path, fileSize: r.fileSize, suffix: r.suffix, contentType: r.contentType, status: 0, thumbnailPath: null, createdAt: r.createdAt, updatedAt: r.updatedAt }));
                    const idx = fileNodes.findIndex(f => f.id === item.fileId);
                    setPreview({ files: fileNodes, index: idx >= 0 ? idx : 0 });
                  }} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); (e.currentTarget as HTMLElement).click(); } }} className="group flex items-start gap-3.5 py-3 px-4 hover:bg-surface-2/70 rounded-none cursor-pointer transition-colors duration-150 animate-file-enter focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                    <div className="flex-shrink-0 mt-0.5"><FileTypeIcon config={config} size="lg" isFolder={isFolder} suffix={item.suffix} /></div>
                    <div className="flex-1 min-w-0">
                      <h3 className="text-sm font-medium text-fg truncate group-hover:text-primary-600 transition-colors"><span className="search-highlight" dangerouslySetInnerHTML={{ __html: sanitizeHighlight(item.fileName) }} /></h3>
                      {item.path && (<div className="flex items-center gap-1 mt-0.5 text-xs text-muted"><FolderOpen className="w-3 h-3 flex-shrink-0" aria-hidden /><span className="truncate max-w-[400px]" title={item.path}>{parentPath || '/'}</span></div>)}
                      {item.highlight && (<div className="search-highlight mt-1.5 text-sm text-muted leading-relaxed line-clamp-2" dangerouslySetInnerHTML={{ __html: sanitizeHighlight(item.highlight) }} />)}
                      <div className="flex items-center gap-2 mt-1.5 text-xs text-muted">
                        <span className={cn('inline-flex items-center px-1.5 py-0.5 rounded font-medium tabular-nums', isFolder ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400' : 'bg-surface-2 text-muted')}>{config.label}</span>
                        {item.fileSize && Number(item.fileSize) > 0 && (<span className="tabular-nums">{formatSize(item.fileSize)}</span>)}
                        {item.updatedAt && (<><span className="text-muted/60">&middot;</span><span className="tabular-nums">{formatDate(item.updatedAt)}</span></>)}
                      </div>
                    </div>
                    <ChevronRight className="w-5 h-5 text-muted/60 flex-shrink-0 mt-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150" aria-hidden />
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
      {preview && (<PreviewModal files={preview.files} currentIndex={preview.index} onClose={() => setPreview(null)} />)}
    </div>
  );
}

type TeamFolderState = 'none' | 'loading' | 'valid' | 'invalid';

function classifyTeamSearchError(error: unknown): SearchErrorState {
  const value = error as { code?: unknown; message?: unknown } | null;
  const message = `${String(value?.code ?? '')} ${String(value?.message ?? (error instanceof Error ? error.message : ''))}`;
  if (message.includes('SEARCH_UNAVAILABLE') || message.includes('搜索暂时不可用')) {
    return { kind: 'unavailable', message: '搜索暂时不可用，请稍后重试' };
  }
  if (message.includes('SEARCH_SCOPE_TOO_LARGE') || message.includes('搜索范围较大')) {
    return { kind: 'scope', message: '搜索范围较大，请选择文件夹或增加筛选条件' };
  }
  if (message.includes('SEARCH_CURSOR_EXPIRED') || message.includes('SEARCH_CURSOR_INVALID') || message.includes('搜索已过期')) {
    return { kind: 'cursor', message: '搜索已过期，请重新搜索' };
  }
  if (message.includes('FORBIDDEN') || message.includes('无权') || message.includes('无权限') || message.includes('空间不存在')) {
    return { kind: 'generic', message: '无权访问该空间或空间已不存在' };
  }
  return { kind: 'generic', message: error instanceof Error && error.message ? error.message : '搜索失败，请稍后重试' };
}

/** 团队搜索分支：独立游标和请求代际，避免个人分页契约与结果互相污染。 */
function TeamSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const isMobile = useMobile();
  const rawFileType = searchParams.get('fileType') || '';
  const urlFileType = FILE_TYPE_TABS.some((item) => item.value === rawFileType) ? rawFileType as FileTypeFilter : 'all';
  const urlKeyword = searchParams.get('keyword') || '';
  const urlNonce = searchParams.get('_t') || '';
  const urlSpaceId = searchParams.get('spaceId') || '';
  const urlFolderId = searchParams.get('folderId');

  const [keyword, setKeyword] = useState(urlKeyword);
  const [fileType, setFileType] = useState<FileTypeFilter>(urlFileType);
  const [sizeFilter, setSizeFilter] = useState<SizeFilter>('all');
  const [dateRange, setDateRange] = useState<DateRange | undefined>(undefined);
  const [results, setResults] = useState<TeamSearchResultVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [searched, setSearched] = useState(Boolean(urlKeyword));
  const [searchTime, setSearchTime] = useState(0);
  const [searchError, setSearchError] = useState<SearchErrorState | null>(null);
  const [spaces, setSpaces] = useState<TeamSpace[]>([]);
  const [spacesLoading, setSpacesLoading] = useState(true);
  const [spacesError, setSpacesError] = useState<string | null>(null);
  const [folderState, setFolderState] = useState<TeamFolderState>('none');
  const [folderName, setFolderName] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);

  const requestSeqRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const loadingMoreRef = useRef(false);

  useEffect(() => {
    setKeyword(urlKeyword);
    setFileType(urlFileType);
  }, [urlFileType, urlKeyword, urlNonce]);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    setSpacesLoading(true);
    setSpacesError(null);
    api.get<PageResult<TeamSpace>>('/team/spaces', { params: { page: 1, size: 50 }, signal: controller.signal })
      .then((response) => { if (!cancelled) setSpaces(response?.records || []); })
      .catch((error) => {
        if (!cancelled && !isAbortError(error)) {
          setSpaces([]);
          setSpacesError(error instanceof Error && error.message ? error.message : '团队空间加载失败');
        }
      })
      .finally(() => { if (!cancelled) setSpacesLoading(false); });
    return () => { cancelled = true; controller.abort(); };
  }, []);

  // 先通过团队 source 读取 folderId，服务端核权后才允许提交搜索请求。
  useEffect(() => {
    if (!urlFolderId) {
      setFolderState('none');
      setFolderName(null);
      return;
    }
    if (!urlSpaceId) {
      setFolderState('invalid');
      setFolderName(null);
      return;
    }
    let cancelled = false;
    setFolderState('loading');
    setFolderName(null);
    teamFileSource(urlSpaceId).getNodeById(urlFolderId)
      .then((folder) => {
        if (cancelled) return;
        if (!folder || folder.nodeType !== 0) {
          setFolderState('invalid');
          return;
        }
        setFolderState('valid');
        setFolderName(folder.name);
      })
      .catch(() => { if (!cancelled) setFolderState('invalid'); });
    return () => { cancelled = true; };
  }, [urlFolderId, urlSpaceId]);

  const executeSearch = useCallback(async (append: boolean, nextCursor: string | null) => {
    if (!urlKeyword.trim() || !urlSpaceId) return;
    if (urlFolderId && folderState !== 'valid') return;
    if (append && (!nextCursor || loadingMoreRef.current)) return;
    if (!append) controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestSeq = ++requestSeqRef.current;
    const t0 = Date.now();
    if (append) {
      loadingMoreRef.current = true;
      setLoadingMore(true);
    } else {
      setLoading(true);
      setSearchError(null);
      setResults([]);
      setCursor(null);
      setHasMore(false);
    }
    try {
      const params: Record<string, unknown> = { spaceId: urlSpaceId, keyword: urlKeyword.trim(), size: 20 };
      if (urlFolderId) params.folderId = urlFolderId;
      if (nextCursor) params.cursor = nextCursor;
      if (urlFileType === 'folder') params.nodeType = 0;
      else if (urlFileType !== 'all') params.suffixes = FILTER_SUFFIXES[urlFileType].join(',');
      const sizeParams = getSizeParams(sizeFilter);
      if (sizeParams.min !== undefined) params.sizeMin = sizeParams.min;
      if (sizeParams.max !== undefined) params.sizeMax = sizeParams.max;
      if (dateRange) {
        params.dateFrom = dateRange.from.getTime();
        params.dateTo = dateRange.to.getTime();
      }
      const response = await api.get<TeamSearchResultPage>('/search/team', { params, signal: controller.signal });
      if (requestSeq !== requestSeqRef.current) return;
      const incoming = response?.records || [];
      setResults((previous) => {
        if (!append) return incoming;
        const seen = new Set(previous.map((item) => item.fileId));
        return [...previous, ...incoming.filter((item) => !seen.has(item.fileId))];
      });
      setCursor(response?.nextCursor || null);
      setHasMore(Boolean(response?.hasMore));
      setSearchTime(Date.now() - t0);
      setSearchError(null);
    } catch (error) {
      if (isAbortError(error) || requestSeq !== requestSeqRef.current) return;
      const failure = classifyTeamSearchError(error);
      setSearchError(failure);
      if (failure.kind === 'cursor') {
        setCursor(null);
        setHasMore(false);
      }
      if (!append) setResults([]);
      setSearchTime(Date.now() - t0);
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
        if (append) setLoadingMore(false);
      }
      if (append) loadingMoreRef.current = false;
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }, [dateRange, folderState, sizeFilter, urlFileType, urlFolderId, urlKeyword, urlSpaceId]);

  useEffect(() => {
    controllerRef.current?.abort();
    requestSeqRef.current += 1;
    loadingMoreRef.current = false;
    setLoadingMore(false);
    setSearched(Boolean(urlKeyword.trim()));
    if (!urlKeyword.trim() || !urlSpaceId || (urlFolderId && folderState !== 'valid')) {
      setResults([]);
      setCursor(null);
      setHasMore(false);
      setLoading(folderState === 'loading');
      setSearchError(null);
      return () => {
        controllerRef.current?.abort();
        requestSeqRef.current += 1;
        loadingMoreRef.current = false;
      };
    }
    void executeSearch(false, null);
    return () => {
      // 切换个人/团队范围或筛选条件时，取消未完成请求并使迟到响应失效。
      controllerRef.current?.abort();
      requestSeqRef.current += 1;
      loadingMoreRef.current = false;
    };
  }, [executeSearch, folderState, urlFolderId, urlKeyword, urlNonce, urlSpaceId]);

  const triggerSearch = (value: string) => {
    const trimmed = value.trim();
    if (!trimmed) return;
    const next = new URLSearchParams(searchParams);
    next.set('keyword', trimmed);
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: trimmed === urlKeyword });
  };

  const changeScope = (scope: SearchScope) => {
    const next = new URLSearchParams(searchParams);
    if (scope === 'personal') {
      next.delete('scope');
      next.delete('spaceId');
      next.delete('folderId');
    } else {
      next.set('scope', 'team');
      if (!next.get('spaceId') && spaces[0]) next.set('spaceId', spaces[0].id);
    }
    next.delete('fileType');
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
  };

  const changeTeamSpace = (spaceId: string) => {
    const next = new URLSearchParams(searchParams);
    if (spaceId) next.set('spaceId', spaceId); else next.delete('spaceId');
    next.delete('folderId');
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
    setResults([]);
    setCursor(null);
    setHasMore(false);
  };

  const clearFolderScope = () => {
    const next = new URLSearchParams(searchParams);
    next.delete('folderId');
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
  };

  const updateFileType = (value: FileTypeFilter) => {
    setFileType(value);
    const next = new URLSearchParams(searchParams);
    if (value === 'all') next.delete('fileType'); else next.set('fileType', value);
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
  };

  const clearFilters = () => {
    setFileType('all');
    setSizeFilter('all');
    setDateRange(undefined);
    const next = new URLSearchParams(searchParams);
    next.delete('fileType');
    next.set('_t', String(Date.now()));
    setSearchParams(next, { replace: true });
  };

  const loadMore = () => {
    if (loading || loadingMore || !hasMore || !cursor) return;
    void executeSearch(true, cursor);
  };

  const openResult = (item: TeamSearchResultVO) => {
    const isFolder = item.nodeType === 0 || (item.nodeType == null && !item.suffix);
    const targetSpaceId = item.spaceId || urlSpaceId;
    if (!targetSpaceId) return;
    if (isFolder) {
      navigate(`/team/${encodeURIComponent(targetSpaceId)}?folderId=${encodeURIComponent(item.fileId)}`);
      return;
    }
    const targetParams = new URLSearchParams();
    if (item.parentId && item.parentId !== '0') targetParams.set('folderId', item.parentId);
    targetParams.set('nodeId', item.fileId);
    navigate(`/team/${encodeURIComponent(targetSpaceId)}?${targetParams.toString()}`);
  };

  const selectedSpace = spaces.find((space) => space.id === urlSpaceId);
  const hasActiveFilters = fileType !== 'all' || sizeFilter !== 'all' || dateRange !== undefined;

  return (
    <div className="flex h-full flex-col bg-surface-2">
      <div className="flex-shrink-0 border-b border-border bg-surface px-4 py-4 sm:px-6">
        <div className="mx-auto max-w-4xl">
          <div className="group relative">
            <Search className="absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-muted transition-colors group-focus-within:text-primary-600" aria-hidden />
            <input type="text" value={keyword} onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') triggerSearch(keyword); }} placeholder="搜索团队文件名或文档内容…" autoFocus={!isMobile} aria-label="搜索团队文件" className="w-full rounded-xl border border-border bg-surface-2 py-2.5 pl-12 pr-24 text-base text-fg outline-none transition focus:border-primary-400 focus:bg-surface focus:ring-2 focus:ring-primary-100" />
            <button type="button" onClick={() => triggerSearch(keyword)} className="absolute right-2 top-1/2 -translate-y-1/2 cursor-pointer rounded-lg bg-primary-600 px-3 py-1 text-sm font-medium text-white transition-colors hover:bg-primary-700 active:bg-primary-800">搜索</button>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2" role="group" aria-label="搜索范围">
            <div className="flex items-center gap-1 rounded-lg bg-surface-2 p-0.5">
              <button type="button" onClick={() => changeScope('personal')} className="cursor-pointer rounded-md px-3 py-1.5 text-xs font-medium text-muted transition-colors hover:text-fg">个人文件</button>
              <button type="button" className="cursor-pointer rounded-md bg-surface px-3 py-1.5 text-xs font-medium text-primary-600 shadow-sm" aria-pressed="true">团队文件</button>
            </div>
            <select value={urlSpaceId} onChange={(e) => changeTeamSpace(e.target.value)} disabled={spacesLoading} aria-label="选择团队空间" className="input-field h-8 w-auto min-w-[10rem] max-w-full py-1 text-xs disabled:cursor-not-allowed disabled:opacity-60">
              <option value="">{spacesLoading ? '加载团队空间…' : '选择团队空间'}</option>
              {spaces.map((space) => <option key={space.id} value={space.id}>{space.spaceName}</option>)}
            </select>
            {urlFolderId && <button type="button" onClick={clearFolderScope} className="flex max-w-full cursor-pointer items-center gap-1 rounded-lg border border-primary-200 bg-primary-500/10 px-2.5 py-1.5 text-xs text-primary-600 transition-colors hover:bg-primary-500/15" title="切换到整个空间"><FolderOpen className="h-3.5 w-3.5 flex-shrink-0" aria-hidden /><span className="max-w-[12rem] truncate">{folderName || '当前文件夹及子文件夹'}</span><X className="h-3 w-3 flex-shrink-0" aria-hidden /></button>}
            {spacesError && <span className="text-xs text-danger">{spacesError}</span>}
          </div>
          {searched && <div className="mt-2.5 flex flex-wrap items-center gap-2 text-sm"><span className="text-muted">团队搜索</span><span className="font-medium text-fg">{urlKeyword === '*' ? '全部文件' : `“${urlKeyword}”`}</span>{selectedSpace && <><span className="text-muted/60">·</span><span className="text-muted">{selectedSpace.spaceName}</span></>}{urlFolderId && <><span className="text-muted/60">·</span><span className="text-muted">当前文件夹及子文件夹</span></>}{!loading && !searchError && <><span className="text-muted/60">·</span><span className="text-muted">已加载 {results.length} 项{searchTime > 0 ? ` · 耗时 ${searchTime}ms` : ''}</span>{hasActiveFilters && <button type="button" onClick={clearFilters} className="ml-2 flex cursor-pointer items-center gap-1 text-xs text-primary-600 hover:text-primary-700"><RotateCcw className="h-3 w-3" aria-hidden />清除筛选</button>}</>}</div>}
        </div>
      </div>

      {searched && urlSpaceId && <div className="relative z-20 flex-shrink-0 overflow-visible border-b border-border bg-surface px-4 py-2.5 sm:px-6"><div className="mx-auto flex max-w-4xl flex-wrap items-center gap-2"><div className="w-full min-w-0 overflow-x-auto rounded-lg bg-surface-2 p-0.5 scrollbar-hide sm:w-auto" role="group" aria-label="文件类型"><div className="flex w-max min-w-full items-center gap-1">{FILE_TYPE_TABS.map((tab) => <button type="button" key={tab.value} onClick={() => updateFileType(tab.value)} aria-pressed={fileType === tab.value} className={cn('flex flex-none cursor-pointer items-center gap-1 rounded-md px-2.5 py-1 text-xs font-medium whitespace-nowrap transition-colors', fileType === tab.value ? 'bg-surface text-primary-600 shadow-sm' : 'text-muted hover:text-fg')}><tab.icon className="h-3.5 w-3.5 flex-shrink-0" aria-hidden /><span className="whitespace-nowrap">{tab.label}</span></button>)}</div></div><FilterDropdown icon={HardDrive} label="大小" options={SIZE_OPTIONS} value={sizeFilter} onChange={(value) => setSizeFilter(value as SizeFilter)} /><DateRangeFilter value={dateRange} onChange={setDateRange} /></div></div>}

      <div className="min-h-0 flex-1 overflow-auto"><div className="mx-auto max-w-4xl px-4 py-4 sm:px-6">
        {loading ? <div className="divide-y divide-stone-50 overflow-hidden rounded-xl bg-surface">{Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}</div>
          : !urlSpaceId ? <div className="flex flex-col items-center justify-center py-20 text-center text-muted"><Users className="mb-4 h-12 w-12 text-muted/40" strokeWidth={1.3} aria-hidden /><p className="text-base font-medium text-fg">请选择团队空间</p><p className="mt-2 text-sm text-muted">选择已加入的空间后，搜索团队文件名和文档内容</p></div>
          : folderState === 'invalid' ? <div className="flex flex-col items-center justify-center py-20 text-center text-muted"><AlertTriangle className="mb-4 h-12 w-12 text-amber-500/70" strokeWidth={1.3} aria-hidden /><p className="text-base font-medium text-fg">无权访问该文件夹</p><p className="mt-2 text-sm text-muted">请切换到整个空间或选择其他可访问文件夹</p></div>
          : searchError && results.length === 0 ? <div className="flex flex-col items-center justify-center py-20 text-center text-muted"><AlertTriangle className="mb-4 h-12 w-12 text-amber-500/70" strokeWidth={1.3} aria-hidden /><p className="text-base font-medium text-fg">{searchError.kind === 'unavailable' ? '搜索暂时不可用' : searchError.kind === 'scope' ? '搜索范围较大' : searchError.kind === 'cursor' ? '搜索已过期' : '搜索失败'}</p><p className="mt-2 max-w-sm text-sm text-muted">{searchError.message}</p>{searchError.kind === 'cursor' && <button type="button" onClick={() => triggerSearch(urlKeyword)} className="btn-primary mt-4">重新搜索</button>}</div>
          : !searched ? <div className="flex flex-col items-center justify-center py-20 text-center text-muted"><div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-surface-2"><Search className="h-8 w-8 text-muted/60" strokeWidth={1.2} aria-hidden /></div><p className="text-base font-medium text-fg">开始你的团队搜索</p><p className="mt-2 text-sm text-muted">输入关键词，搜索当前空间中有权访问的文件</p></div>
          : results.length === 0 ? <div className="flex flex-col items-center justify-center py-20 text-center text-muted"><div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-surface-2"><SlidersHorizontal className="h-8 w-8 text-muted/60" strokeWidth={1.2} aria-hidden /></div><p className="text-base font-medium text-fg">未找到相关文件</p><p className="mt-2 text-sm text-muted">可调整关键词或搜索范围；新文件索引可能尚未完成</p></div>
          : <>
            {searchError && <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-amber-200 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-300" role="alert"><AlertTriangle className="h-4 w-4 flex-shrink-0" aria-hidden /><span className="min-w-0 flex-1">{searchError.message}</span>{searchError.kind === 'cursor' && <button type="button" onClick={() => triggerSearch(urlKeyword)} className="btn-secondary flex-shrink-0">重新搜索</button>}</div>}
            <div className="divide-y divide-stone-50 overflow-hidden rounded-xl bg-surface">{results.map((item) => { const isFolder = item.nodeType === 0 || (item.nodeType == null && !item.suffix); const config = getFileTypeConfig(isFolder ? 0 : 1, item.suffix); const parentPath = item.path ? (item.path.lastIndexOf('/') > 0 ? item.path.substring(0, item.path.lastIndexOf('/')) : '/') : ''; return <button type="button" key={item.fileId} onClick={() => openResult(item)} className="group flex w-full cursor-pointer items-start gap-3.5 px-4 py-3 text-left transition-colors hover:bg-surface-2/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"><div className="mt-0.5 flex-shrink-0"><FileTypeIcon config={config} size="lg" isFolder={isFolder} suffix={item.suffix} /></div><div className="min-w-0 flex-1"><h3 className="truncate text-sm font-medium text-fg transition-colors group-hover:text-primary-600"><span className="search-highlight" dangerouslySetInnerHTML={{ __html: sanitizeHighlight(item.fileName) }} /></h3>{item.path && <div className="mt-0.5 flex items-center gap-1 text-xs text-muted"><FolderOpen className="h-3 w-3 flex-shrink-0" aria-hidden /><span className="max-w-[34rem] truncate" title={item.path}>{parentPath || '/'}</span></div>}{item.highlight && <div className="search-highlight mt-1.5 line-clamp-2 text-sm leading-relaxed text-muted" dangerouslySetInnerHTML={{ __html: sanitizeHighlight(item.highlight) }} />}<div className="mt-1.5 flex items-center gap-2 text-xs text-muted"><span className={cn('inline-flex items-center rounded px-1.5 py-0.5 font-medium tabular-nums', isFolder ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400' : 'bg-surface-2 text-muted')}>{config.label}</span>{item.fileSize && Number(item.fileSize) > 0 && <span className="tabular-nums">{formatSize(item.fileSize)}</span>}<span className="text-muted/60">·</span><span className="tabular-nums">{formatDate(item.updatedAt)}</span></div></div><ChevronRight className="mt-1 h-5 w-5 flex-shrink-0 text-muted/60 opacity-0 transition-opacity group-hover:opacity-100" aria-hidden /></button>; })}</div>
            {hasMore && <button type="button" onClick={loadMore} disabled={loadingMore} className="btn-secondary mt-4 w-full">{loadingMore ? <><Loader2 className="h-4 w-4 animate-spin" aria-hidden />加载中…</> : '加载更多'}</button>}
          </>
        }
      </div></div>
    </div>
  );
}

export default function SearchPage() {
  const [searchParams] = useSearchParams();
  return searchParams.get('scope') === 'team' ? <TeamSearchPage /> : <PersonalSearchPage />;
}
