import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../lib/api';
import { useToast } from '../components/ui/Toast';
import type { FileWatchState } from '../types';

interface UseFileWatchResult {
  watching: boolean;
  watchId: string | null;
  loading: boolean;
  saving: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  toggle: () => Promise<void>;
}

function isAbortError(error: unknown): boolean {
  const value = error as { code?: string; name?: string } | null;
  return value?.code === 'ERR_CANCELED' || value?.name === 'AbortError';
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  return fallback;
}

function normalizeState(value: Partial<FileWatchState> | null | undefined, fallbackNodeId: string): FileWatchState {
  return {
    nodeId: String(value?.nodeId ?? fallbackNodeId),
    watching: Boolean(value?.watching),
    watchId: value?.watchId == null ? null : String(value.watchId),
  };
}

/**
 * 详情面板专用关注状态：请求按 nodeId 绑定并使用序号/AbortController，
 * 节点切换或组件卸载后，旧响应不能覆盖当前节点的关注状态。
 */
export function useFileWatch(nodeId: string | null | undefined): UseFileWatchResult {
  const { showToast } = useToast();
  const [watchState, setWatchState] = useState<FileWatchState | null>(null);
  const [loading, setLoading] = useState(Boolean(nodeId));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const nodeIdRef = useRef(nodeId);

  useEffect(() => {
    nodeIdRef.current = nodeId;
  }, [nodeId]);

  const refresh = useCallback(async () => {
    if (!nodeId) {
      setWatchState(null);
      setLoading(false);
      setError(null);
      return;
    }

    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestSeq = ++requestSeqRef.current;
    setLoading(true);
    setError(null);

    try {
      const value = await api.get<FileWatchState>('/file-watches/state', {
        params: { nodeId },
        signal: controller.signal,
      });
      if (requestSeq !== requestSeqRef.current || nodeIdRef.current !== nodeId) return;
      setWatchState(normalizeState(value, nodeId));
    } catch (err) {
      if (isAbortError(err) || requestSeq !== requestSeqRef.current || nodeIdRef.current !== nodeId) return;
      setWatchState(normalizeState(null, nodeId));
      setError(errorMessage(err, '获取关注状态失败'));
    } finally {
      if (requestSeq === requestSeqRef.current && nodeIdRef.current === nodeId) {
        setLoading(false);
      }
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }, [nodeId]);

  useEffect(() => {
    setWatchState(null);
    setError(null);
    // 节点切换开启新的写请求代际；旧节点的 finally 不得污染当前按钮状态。
    setSaving(false);
    setLoading(Boolean(nodeId));
    void refresh();

    return () => {
      requestSeqRef.current += 1;
      controllerRef.current?.abort();
    };
  }, [nodeId, refresh]);

  useEffect(() => {
    const onWatchChanged = (event: Event) => {
      if ((event as CustomEvent<{ nodeId: string }>).detail?.nodeId === nodeId) void refresh();
    };
    window.addEventListener('file-watch-changed', onWatchChanged);
    return () => window.removeEventListener('file-watch-changed', onWatchChanged);
  }, [nodeId, refresh]);

  const toggle = useCallback(async () => {
    if (!nodeId || loading || saving) return;

    const currentSeq = requestSeqRef.current;
    const shouldWatch = !watchState?.watching;
    setSaving(true);
    setError(null);
    try {
      const value = shouldWatch
        ? await api.put<FileWatchState>(`/file-watches/${encodeURIComponent(nodeId)}`, undefined)
        : await api.delete<FileWatchState>(`/file-watches/${encodeURIComponent(nodeId)}`);
      if (currentSeq !== requestSeqRef.current || nodeIdRef.current !== nodeId) return;
      const nextState = normalizeState(value, nodeId);
      // DELETE 契约允许无响应体，且成功后的语义固定为当前节点未关注。
      if (!shouldWatch) {
        nextState.watching = false;
        nextState.watchId = null;
      }
      setWatchState(nextState);
      showToast(
        shouldWatch ? '已关注，后续变更将在站内通知中提醒' : '已取消关注',
        'success',
      );
    } catch (err) {
      if (isAbortError(err) || currentSeq !== requestSeqRef.current || nodeIdRef.current !== nodeId) return;
      const message = errorMessage(err, shouldWatch ? '关注失败' : '取消关注失败');
      setError(message);
      showToast(message, 'error');
    } finally {
      if (currentSeq === requestSeqRef.current && nodeIdRef.current === nodeId) setSaving(false);
    }
  }, [loading, nodeId, saving, showToast, watchState?.watching]);

  return {
    watching: Boolean(watchState?.watching),
    watchId: watchState?.watchId ?? null,
    loading,
    saving,
    error,
    refresh,
    toggle,
  };
}

export default useFileWatch;
