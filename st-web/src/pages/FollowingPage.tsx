import { useCallback, useEffect, useRef, useState } from 'react';
import { BellOff, BellRing, FileText, FolderOpen, Loader2, RefreshCw, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import api from '../lib/api';
import { useToast } from '../components/ui/Toast';
import { formatDate, cn } from '../lib/utils';
import type { FileWatchPage, FileWatchRecord } from '../types';

const PAGE_SIZE = 20;

function numberValue(value: number | string | null | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function isRecordAvailable(record: FileWatchRecord): boolean {
  // 新接口以 available=true 明确表示服务端已实时核权；其他值都按失效处理。
  return record.available === true;
}

function buildRecordTarget(record: FileWatchRecord): string | null {
  if (!isRecordAvailable(record) || !record.nodeId) return null;
  if (record.spaceId) {
    const params = new URLSearchParams();
    if (record.nodeType === 0) {
      params.set('folderId', record.nodeId);
      return `/team/${encodeURIComponent(record.spaceId)}?${params.toString()}`;
    }
    if (record.parentId && record.parentId !== '0') params.set('folderId', record.parentId);
    params.set('nodeId', record.nodeId);
    return `/team/${encodeURIComponent(record.spaceId)}?${params.toString()}`;
  }
  if (record.nodeType === 0) return `/files/${encodeURIComponent(record.nodeId)}`;
  const parent = record.parentId && record.parentId !== '0' ? `/${encodeURIComponent(record.parentId)}` : '';
  return `/files${parent}?focusId=${encodeURIComponent(record.nodeId)}`;
}

function SkeletonRow() {
  return (
    <div className="flex items-center gap-4 px-4 py-4 sm:px-6">
      <div className="h-11 w-11 flex-shrink-0 rounded-xl bg-surface-2 shimmer" />
      <div className="min-w-0 flex-1 space-y-2.5">
        <div className="h-3.5 w-1/3 rounded bg-surface-2 shimmer" />
        <div className="h-3 w-2/3 rounded bg-surface-2 shimmer" />
      </div>
    </div>
  );
}

export default function FollowingPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [records, setRecords] = useState<FileWatchRecord[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const requestSeqRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);

  const loadPage = useCallback(async (targetPage: number, append: boolean): Promise<boolean> => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestSeq = ++requestSeqRef.current;
    let loaded = false;
    if (append) setLoadingMore(true);
    else setLoading(true);
    setLoadError(null);

    try {
      const response = await api.get<FileWatchPage>('/file-watches', {
        params: { page: targetPage, size: PAGE_SIZE },
        signal: controller.signal,
      });
      if (requestSeq !== requestSeqRef.current) return false;
      const incoming = response?.records || [];
      setRecords((previous) => {
        if (!append) return incoming;
        const seen = new Set(previous.map((item) => item.watchId));
        return [...previous, ...incoming.filter((item) => !seen.has(item.watchId))];
      });
      setPage(targetPage);
      setTotal(numberValue(response?.total, 0));
      const pages = numberValue(response?.pages, 0);
      setHasMore(pages > targetPage || (pages === 0 && incoming.length >= PAGE_SIZE));
      loaded = true;
    } catch (error) {
      if (controller.signal.aborted || requestSeq !== requestSeqRef.current) return false;
      setLoadError(error instanceof Error && error.message ? error.message : '关注列表加载失败');
      if (!append) setRecords([]);
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
        setLoadingMore(false);
      }
      if (controllerRef.current === controller) controllerRef.current = null;
    }
    return loaded;
  }, []);

  /**
   * 删除会让后续页整体前移。重新读取已经展开的页，避免直接保留旧页或跳到下一页造成漏项。
   */
  const reloadLoadedPages = useCallback(async (lastPage: number) => {
    const pageCount = Math.max(1, lastPage);
    if (!(await loadPage(1, false))) return;
    for (let nextPage = 2; nextPage <= pageCount; nextPage += 1) {
      if (!(await loadPage(nextPage, true))) break;
    }
  }, [loadPage]);

  useEffect(() => {
    void loadPage(1, false);
    return () => {
      requestSeqRef.current += 1;
      controllerRef.current?.abort();
    };
  }, [loadPage]);

  const handleRemove = useCallback(async (record: FileWatchRecord) => {
    if (removingId) return;
    setRemovingId(record.watchId);
    try {
      await api.delete(`/file-watches/${encodeURIComponent(record.nodeId)}`);
      setRecords((previous) => previous.filter((item) => item.watchId !== record.watchId));
      setTotal((value) => Math.max(0, value - 1));
      showToast('已取消关注', 'success');
      // 当前页发生 offset 位移，重读此前展开的所有页，避免直接 page+1 漏掉后一条记录。
      await reloadLoadedPages(page);
    } catch (error) {
      showToast(error instanceof Error && error.message ? error.message : '取消关注失败', 'error');
    } finally {
      setRemovingId(null);
    }
  }, [page, reloadLoadedPages, removingId, showToast]);

  const empty = !loading && !loadError && records.length === 0;

  return (
    <div className="h-full overflow-y-auto bg-bg">
      <div className="mx-auto w-full max-w-5xl px-4 pb-10 pt-6 sm:px-8 sm:pt-8">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-4">
            <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-2xl bg-primary-500/10 text-primary-600 ring-1 ring-primary-500/10">
              <BellRing className="h-6 w-6" strokeWidth={1.8} aria-hidden />
            </div>
            <div className="min-w-0">
              <h1 className="text-2xl font-semibold tracking-tight text-fg">我的关注</h1>
              <p className="mt-1 text-sm text-muted">文件或文件夹有新动态时，在通知中及时查看</p>
            </div>
          </div>
          {!loading && !loadError && (
            <div className="rounded-xl border border-border bg-surface px-4 py-2 text-sm text-muted shadow-soft">
              <span className="mr-1 font-semibold tabular-nums text-fg">{total}</span> 个关注
            </div>
          )}
        </div>

        <div className="overflow-hidden rounded-2xl border border-border bg-surface shadow-card">
          <div className="flex items-center justify-between gap-4 border-b border-border px-4 py-4 sm:px-6">
            <div>
              <h2 className="text-sm font-semibold text-fg">关注的内容</h2>
              <p className="mt-0.5 text-xs text-muted">点击名称可打开，取消关注后不再接收该内容的变更提醒</p>
            </div>
            <button type="button" onClick={() => void reloadLoadedPages(page)} disabled={loading || loadingMore} className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg border border-border text-muted transition-colors hover:border-primary-300 hover:bg-primary-500/5 hover:text-primary-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50" aria-label="刷新关注列表" title="刷新关注列表">
              <RefreshCw className="h-4 w-4" aria-hidden />
            </button>
          </div>
          {loading ? (
            <div className="divide-y divide-border-light" role="status" aria-label="正在加载关注列表">
              {Array.from({ length: 4 }).map((_, index) => <SkeletonRow key={index} />)}
            </div>
          ) : loadError ? (
            <div className="flex flex-col items-center justify-center px-6 py-16 text-center" role="alert">
              <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-danger-light text-danger"><X className="h-6 w-6" aria-hidden /></div>
              <p className="text-sm font-semibold text-fg">关注列表加载失败</p>
              <p className="mt-1 max-w-sm text-sm text-muted">{loadError}</p>
              <button type="button" onClick={() => void loadPage(1, false)} className="btn-secondary mt-5">
                <RefreshCw className="h-4 w-4" aria-hidden /> 重试
              </button>
            </div>
          ) : empty ? (
            <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary-500/10 text-primary-600"><BellRing className="h-8 w-8" strokeWidth={1.5} aria-hidden /></div>
              <p className="text-base font-semibold text-fg">还没有关注内容</p>
              <p className="mt-1 max-w-sm text-sm text-muted">在文件或文件夹的右键菜单中选择“关注变更”，这里就会显示它</p>
              <button type="button" onClick={() => navigate('/files')} className="btn-primary mt-5">去浏览文件</button>
            </div>
          ) : (
            <>
              <div className="divide-y divide-border-light">
                {records.map((record) => {
                  const available = isRecordAvailable(record);
                  const target = buildRecordTarget(record);
                  const removeBusy = removingId === record.watchId;
                  const isTeam = Boolean(available && record.spaceId);
                  return (
                    <div key={record.watchId} className={cn('flex items-start gap-3 px-4 py-4 transition-colors sm:items-center sm:gap-4 sm:px-6', available ? 'hover:bg-bg-hover' : 'bg-surface-2/40')}>
                      <div className={cn('flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl', available ? 'bg-primary-500/10 text-primary-600' : 'bg-surface-2 text-muted')}>
                        {record.nodeType === 0 ? <FolderOpen className="h-5 w-5" strokeWidth={1.8} aria-hidden /> : <FileText className="h-5 w-5" strokeWidth={1.8} aria-hidden />}
                      </div>
                      {available && target ? (
                        <button type="button" onClick={() => navigate(target)} className="min-w-0 flex-1 rounded-md text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                            <span className="max-w-full truncate text-sm font-semibold text-fg transition-colors hover:text-primary-600">{record.name || '未命名内容'}</span>
                            <span className="rounded-md bg-surface-2 px-1.5 py-0.5 text-[11px] font-medium text-muted">{isTeam ? '团队空间' : '个人文件'}</span>
                          </span>
                          <span className="mt-1.5 block truncate text-xs text-muted">{record.path || '根目录'}</span>
                          <span className="mt-1.5 block text-[11px] text-tertiary">关注于 {formatDate(record.createdAt)}</span>
                        </button>
                      ) : (
                        <div className="min-w-0 flex-1">
                          <span className="block text-sm font-semibold text-muted">内容已不可用</span>
                          <span className="mt-1.5 block text-xs text-muted">当前无权访问或内容已删除</span>
                          <span className="mt-1.5 block text-[11px] text-tertiary">关注于 {formatDate(record.createdAt)}</span>
                        </div>
                      )}
                      <button type="button" onClick={() => void handleRemove(record)} disabled={removeBusy} className="inline-flex h-9 flex-shrink-0 items-center justify-center gap-1.5 rounded-lg border border-border px-2.5 text-xs font-medium text-muted transition-colors hover:border-danger/30 hover:bg-danger-light hover:text-danger focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 sm:px-3" aria-label={available ? `取消关注 ${record.name || '内容'}` : '取消关注不可用内容'}>
                        {removeBusy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <BellOff className="h-4 w-4" aria-hidden />}
                        <span className="hidden sm:inline">取消关注</span>
                      </button>
                    </div>
                  );
                })}
              </div>
              {hasMore && (
                <div className="border-t border-border px-4 py-4 sm:px-6">
                  <button type="button" onClick={() => void loadPage(page + 1, true)} disabled={loadingMore} className="btn-secondary h-9 w-full text-xs">
                    {loadingMore ? <><Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> 加载中…</> : '加载更多'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
