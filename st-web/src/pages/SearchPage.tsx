import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Search, ChevronRight, FolderOpen, SlidersHorizontal, FileText, Image as ImageIcon, Video, Music, Archive, ArrowDownUp, Calendar, HardDrive, RotateCcw, X } from 'lucide-react';
import { format } from 'date-fns';
import { Calendar as CalendarPicker } from '../components/ui/calendar';
import { Popover, PopoverContent, PopoverTrigger } from '../components/ui/popover';
import api from '../lib/api';
import type { SearchResultVO, FileNode } from '../types';
import { getFileTypeConfig, formatSize, formatDate, cn } from '../lib/utils';
import FileTypeIcon from '../components/file/FileTypeIcon';
import PreviewModal from '../components/preview/PreviewModal';

type FileTypeFilter = 'all' | 'folder' | 'image' | 'video' | 'audio' | 'document' | 'archive';
type SortOption = 'relevance' | 'name' | 'size_desc' | 'size_asc' | 'date_desc' | 'date_asc';
type SizeFilter = 'all' | 'small' | 'medium' | 'large';
type DateRange = { from: Date; to: Date };

const FILTER_SUFFIXES: Record<Exclude<FileTypeFilter, 'all' | 'folder'>, string[]> = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'],
  video: ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'mpg', 'mpeg', '3gp'],
  audio: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'm4a', 'opus'],
  document: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'ppt', 'pptx', 'txt', 'md', 'markdown'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'tgz'],
};

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
      <button onClick={() => setOpen(!open)} className={cn('flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer border', active ? 'bg-primary-50 text-primary-700 border-primary-200' : 'bg-white text-stone-600 border-stone-200 hover:bg-stone-50 hover:border-stone-300')}>
        <Icon className="w-3.5 h-3.5" aria-hidden /><span>{selectedLabel}</span>
      </button>
      {open && (
        <div className="absolute top-full left-0 mt-1 w-44 bg-white rounded-lg border border-stone-200 shadow-lg py-1 z-50 animate-scale-in">
          {options.map((opt) => (
            <button key={opt.value} onClick={() => { onChange(opt.value); setOpen(false); }} className={cn('w-full text-left px-3 py-1.5 text-xs transition-colors cursor-pointer', opt.value === value ? 'text-primary-700 bg-primary-50 font-medium' : 'text-stone-600 hover:bg-stone-50')}>{opt.label}</button>
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
          <button className={cn('flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer border', active ? 'bg-primary-50 text-primary-700 border-primary-200' : 'bg-white text-stone-600 border-stone-200 hover:bg-stone-50 hover:border-stone-300')}>
            <Calendar className="w-3.5 h-3.5" aria-hidden />
            <span>{label}</span>
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start" sideOffset={4}>
          <div className="p-3">
            <div className="flex items-center gap-1.5 mb-3 flex-wrap">
              {QUICK_PRESETS.map((p) => (
                <button key={p.label} onClick={() => setLocal(p.getRange())} className="px-2 py-1 text-xs text-primary-600 hover:text-primary-700 bg-primary-50 hover:bg-primary-100 rounded-md cursor-pointer transition-colors">{p.label}</button>
              ))}
              <button onClick={() => setLocal(undefined)} className="px-2 py-1 text-xs text-stone-500 hover:text-stone-700 bg-stone-50 hover:bg-stone-100 rounded-md cursor-pointer transition-colors">全部</button>
            </div>
            <CalendarPicker mode="range" selected={local ? { from: local.from, to: local.to } : undefined} onSelect={(range: { from?: Date; to?: Date } | undefined) => { if (range?.from) setLocal({ from: range.from, to: range.to || range.from }); else setLocal(undefined); }} numberOfMonths={1} />
            <div className="flex items-center justify-between mt-2 pt-2 border-t border-stone-100">
              <span className="text-xs text-stone-400">{local?.from ? `${format(local.from, 'yyyy-MM-dd')}${local?.to && local.to.getTime() !== local.from.getTime() ? ` ~ ${format(local.to, 'yyyy-MM-dd')}` : ''}` : '请选择日期范围'}</span>
              <div className="flex items-center gap-2">
                <button onClick={() => { setLocal(undefined); onChange(undefined); setOpen(false); }} className="px-2.5 py-1 text-xs text-stone-500 hover:text-stone-700 cursor-pointer transition-colors">清除</button>
                <button onClick={apply} className="px-3 py-1 text-xs font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md cursor-pointer transition-colors">确定</button>
              </div>
            </div>
          </div>
        </PopoverContent>
      </Popover>
      {active && (
        <button onClick={() => onChange(undefined)} className="w-5 h-5 flex items-center justify-center rounded-full hover:bg-stone-200 text-stone-400 hover:text-stone-600 transition-colors cursor-pointer" aria-label="清除时间筛选">
          <X className="w-3 h-3" aria-hidden />
        </button>
      )}
    </div>
  );
}
function SkeletonCard() {
  return (
    <div className="flex items-start gap-3.5 py-3 px-3 rounded-lg border border-transparent">
      <div className="w-7 h-7 rounded-lg bg-stone-200 animate-pulse flex-shrink-0 mt-0.5" />
      <div className="flex-1 min-w-0 space-y-2">
        <div className="h-4 bg-stone-200 rounded animate-pulse w-2/3" />
        <div className="h-3 bg-stone-100 rounded animate-pulse w-1/3" />
        <div className="h-3 bg-stone-100 rounded animate-pulse w-1/2" />
      </div>
    </div>
  );
}

export default function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
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

  const doSearch = useCallback(async (kw: string, ft: FileTypeFilter, sf: SizeFilter) => {
    if (!kw.trim()) { setResults([]); setSearched(false); return; }
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
      const res = await api.get<SearchResultVO[]>('/search', { params });
      setResults(res || []);
      setSearchTime(Date.now() - t0);
    } catch { setResults([]); setSearchTime(Date.now() - t0); } finally { setLoading(false); }
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
    <div className="flex flex-col h-full bg-stone-50">
      {/* Search header */}
      <div className="px-6 py-4 border-b border-stone-200 bg-white flex-shrink-0">
        <div className="max-w-4xl mx-auto">
          <div className="relative group">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-stone-400 group-focus-within:text-primary-600 transition-colors" aria-hidden />
            <input type="text" value={keyword} onChange={(e) => setKeyword(e.target.value)} onKeyDown={handleSearchSubmit} placeholder="搜索文件名或文档内容..." autoFocus className="w-full pl-12 pr-24 py-2.5 bg-stone-50 border border-stone-200 rounded-xl text-base text-stone-900 placeholder-stone-400 outline-none transition focus:bg-white focus:border-primary-400 focus:ring-2 focus:ring-primary-100" />
            <button onClick={() => triggerSearch(keyword)} className="absolute right-2 top-1/2 -translate-y-1/2 px-3 py-1 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 active:bg-primary-800 cursor-pointer transition-colors">搜索</button>
          </div>
          {(searched || urlKeyword) && (
            <div className="flex items-center gap-2 mt-2.5 text-sm">
              <span className="text-stone-500">搜索</span>
              <span className="font-medium text-stone-900">{urlKeyword === '*' ? '\u5168\u90e8\u6587\u4ef6' : '\u201c' + urlKeyword + '\u201d'}</span>
              {!loading && (
                <>
                  <span className="text-stone-300">&middot;</span>
                  <span className="text-stone-400">{dateFiltered.length} 个结果{dateRange && results.length !== dateFiltered.length && ` (共 ${results.length} 条)`}{searchTime > 0 && `\u00b7 \u8017\u65f6 ${searchTime}ms`}</span>
                  {hasActiveFilters && (
                    <button onClick={clearFilters} className="ml-2 flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 cursor-pointer"><RotateCcw className="w-3 h-3" aria-hidden /><span>清除筛选</span></button>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Filter bar */}
      {searched && (
        <div className="px-6 py-2.5 border-b border-stone-200 bg-white flex-shrink-0 relative z-20 overflow-visible">
          <div className="max-w-4xl mx-auto flex items-center gap-2 flex-wrap">
            <div className="flex items-center gap-1 bg-stone-100 rounded-lg p-0.5">
              {FILE_TYPE_TABS.map((tab) => (
                <button key={tab.value} onClick={() => setFileType(tab.value)} className={cn('flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer', fileType === tab.value ? 'bg-white text-primary-700 shadow-sm' : 'text-stone-500 hover:text-stone-700')}>
                  <tab.icon className="w-3.5 h-3.5" aria-hidden /><span>{tab.label}</span>
                </button>
              ))}
            </div>
            <div className="w-px h-5 bg-stone-200" />
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
            <div className="space-y-1">{Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}</div>
          ) : !searched ? (
            <div className="flex flex-col items-center justify-center py-20 text-stone-400">
              <div className="w-16 h-16 rounded-2xl bg-stone-100 flex items-center justify-center mb-4"><Search className="w-8 h-8 text-stone-300" strokeWidth={1.2} aria-hidden /></div>
              <p className="text-base font-medium text-stone-600">开始你的搜索</p>
              <p className="text-sm mt-2 text-stone-400">在上方搜索框输入关键词，支持文件名和文档内容搜索</p>
              <div className="flex items-center gap-2 mt-6">
                {FILE_TYPE_TABS.slice(1).map((tab) => (
                  <div key={tab.value} className="flex items-center gap-1 px-2.5 py-1 bg-white border border-stone-200 rounded-lg text-xs text-stone-500"><tab.icon className="w-3 h-3" aria-hidden /><span>{tab.label}</span></div>
                ))}
              </div>
            </div>
          ) : sortedResults.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-stone-400">
              <div className="w-16 h-16 rounded-2xl bg-stone-100 flex items-center justify-center mb-4"><SlidersHorizontal className="w-8 h-8 text-stone-300" strokeWidth={1.2} aria-hidden /></div>
              <p className="text-base font-medium text-stone-600">{hasActiveFilters ? '没有符合条件的文件' : '未找到相关文件'}</p>
              <p className="text-sm mt-2 text-stone-400">{hasActiveFilters ? '试试调整筛选条件或清除筛选' : '换个关键词试试吧'}</p>
              {hasActiveFilters && (<button onClick={clearFilters} className="mt-4 px-4 py-2 bg-white border border-stone-200 rounded-lg text-sm text-stone-600 hover:bg-stone-50 hover:border-stone-300 transition-colors cursor-pointer">清除所有筛选</button>)}
            </div>
          ) : (
            <div className="space-y-0.5">
              {sortedResults.map((item) => {
                const isFolder = item.nodeType === 0 || (item.nodeType == null && !item.suffix);
                const config = getFileTypeConfig(isFolder ? 0 : 1, item.suffix);
                const parentPath = getParentPath(item.path);
                return (
                  <div key={item.fileId} onClick={() => {
                    if (isFolder) { navigate(`/files/${item.fileId}`); return; }
                    const fileNodes: FileNode[] = sortedResults.filter(r => !(r.nodeType === 0 || (r.nodeType == null && !r.suffix))).map(r => ({ id: r.fileId, parentId: '', nodeType: 1, name: r.fileName.replace(/<[^>]*>/g, ''), path: r.path, fileSize: r.fileSize, suffix: r.suffix, contentType: r.contentType, status: 0, thumbnailPath: null, createdAt: r.createdAt, updatedAt: r.updatedAt }));
                    const idx = fileNodes.findIndex(f => f.id === item.fileId);
                    setPreview({ files: fileNodes, index: idx >= 0 ? idx : 0 });
                  }} className="group flex items-start gap-3.5 py-3 px-3 hover:bg-white hover:shadow-sm rounded-lg cursor-pointer transition duration-150 border border-transparent hover:border-stone-200">
                    <div className="flex-shrink-0 mt-0.5"><FileTypeIcon config={config} size="lg" isFolder={isFolder} suffix={item.suffix} /></div>
                    <div className="flex-1 min-w-0">
                      <h3 className="text-sm font-medium text-stone-900 truncate group-hover:text-primary-600 transition-colors"><span className="search-highlight" dangerouslySetInnerHTML={{ __html: item.fileName }} /></h3>
                      {item.path && (<div className="flex items-center gap-1 mt-0.5 text-xs text-stone-400"><FolderOpen className="w-3 h-3 flex-shrink-0" aria-hidden /><span className="truncate max-w-[400px]" title={item.path}>{parentPath || '/'}</span></div>)}
                      {item.highlight && (<div className="search-highlight mt-1.5 text-sm text-stone-500 leading-relaxed line-clamp-2" dangerouslySetInnerHTML={{ __html: item.highlight }} />)}
                      <div className="flex items-center gap-2 mt-1.5 text-xs text-stone-400">
                        <span className={cn('inline-flex items-center px-1.5 py-0.5 rounded font-medium tabular-nums', isFolder ? 'bg-amber-50 text-amber-600' : 'bg-stone-100 text-stone-500')}>{config.label}</span>
                        {item.fileSize && Number(item.fileSize) > 0 && (<span className="tabular-nums">{formatSize(item.fileSize)}</span>)}
                        {item.updatedAt && (<><span className="text-stone-300">&middot;</span><span className="tabular-nums">{formatDate(item.updatedAt)}</span></>)}
                      </div>
                    </div>
                    <ChevronRight className="w-5 h-5 text-stone-300 flex-shrink-0 mt-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150" aria-hidden />
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