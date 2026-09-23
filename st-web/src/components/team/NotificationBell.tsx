import { useState, useEffect, useRef, useCallback, useLayoutEffect, type CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, BellRing, ChevronRight, Inbox, MessageSquare, Users, AlertTriangle, FileText, X } from 'lucide-react';
import api from '../../lib/api';
import { getServerUrlSync } from '../../lib/server-config';
import { useAuthStore } from '../../store/auth';
import { cn } from '../../lib/utils';
import { useToast } from '../ui/Toast';
import type { NotificationItem, NotificationTarget, PageResult } from '../../types';

const iconMap: Record<string, typeof Bell> = {
  MENTION: MessageSquare,
  TEAM_INVITE: Users,
  MEMBER_CHANGE: AlertTriangle,
  FILE_CHANGE: FileText,
};

const typeLabels: Record<string, string> = {
  MENTION: '有人提及你',
  TEAM_INVITE: '团队邀请',
  MEMBER_CHANGE: '团队动态',
  FILE_CHANGE: '关注动态',
};

const iconTones: Record<string, string> = {
  MENTION: 'bg-info-light text-info',
  TEAM_INVITE: 'bg-success-light text-success',
  MEMBER_CHANGE: 'bg-warning-light text-warning',
  FILE_CHANGE: 'bg-primary-500/10 text-primary-600',
};

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  return new Date(iso).toLocaleDateString('zh-CN');
}

function buildTargetUrl(target: NotificationTarget): string | null {
  if (!target.available || !target.nodeId) return null;
  if (target.spaceId) {
    const params = new URLSearchParams();
    if (target.nodeType === 0) {
      params.set('folderId', target.nodeId);
      return `/team/${encodeURIComponent(target.spaceId)}?${params.toString()}`;
    }
    if (target.parentId && target.parentId !== '0') params.set('folderId', target.parentId);
    params.set('nodeId', target.nodeId);
    return `/team/${encodeURIComponent(target.spaceId)}?${params.toString()}`;
  }
  if (target.nodeType === 0) return `/files/${encodeURIComponent(target.nodeId)}`;
  const parent = target.parentId && target.parentId !== '0' ? `/${encodeURIComponent(target.parentId)}` : '';
  return `/files${parent}?focusId=${encodeURIComponent(target.nodeId)}`;
}

export default function NotificationBell() {
  const navigate = useNavigate();
  const userId = useAuthStore((state) => state.user?.userId);
  const { showToast } = useToast();
  const [unread, setUnread] = useState(0);
  const [showList, setShowList] = useState(false);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [isListLoading, setIsListLoading] = useState(false);
  const [listError, setListError] = useState(false);
  const [markingAll, setMarkingAll] = useState(false);
  const [mobilePanelStyle, setMobilePanelStyle] = useState<CSSProperties>();
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const selectionRef = useRef(0);
  const unreadRequestRef = useRef(0);
  const showListRef = useRef(showList);
  showListRef.current = showList;

  useEffect(() => () => { selectionRef.current += 1; unreadRequestRef.current += 1; }, []);

  const fetchUnread = useCallback(async () => {
    const request = ++unreadRequestRef.current;
    try {
      const count: number = await api.get('/notification/unread-count');
      if (request === unreadRequestRef.current) setUnread(count || 0);
    } catch { /* 定时查询和 WebSocket 重连会继续恢复状态 */ }
  }, []);

  const fetchList = useCallback(async () => {
    setIsListLoading(true);
    setListError(false);
    try {
      const res = await api.get<PageResult<NotificationItem>>('/notification', { params: { page: 1, size: 20 } });
      setNotifications(res?.records || []);
    } catch {
      // 请求失败不能降级成空列表，避免把服务异常误导为“暂无通知”。
      setListError(true);
    } finally {
      setIsListLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!userId) {
      setUnread(0);
      return;
    }
    void fetchUnread();
    const timer = setInterval(fetchUnread, 30000);
    return () => clearInterval(timer);
  }, [fetchUnread, userId]);

  useEffect(() => {
    if (!userId) return;
    let stopped = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
    let reconnectDelay = 1000;

    const connect = () => {
      if (stopped) return;
      const token = sessionStorage.getItem('accessToken');
      if (!token) {
        reconnectTimer = setTimeout(connect, 3000);
        return;
      }
      // 浏览器 WebSocket 不能设置 Authorization 头，服务端握手支持 query token。
      const url = `${getServerUrlSync().replace(/^http/i, 'ws')}/api/sync/ws?token=${encodeURIComponent(token)}`;
      try {
        socket = new WebSocket(url);
      } catch {
        reconnectTimer = setTimeout(connect, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, 30000);
        return;
      }
      const currentSocket = socket;
      currentSocket.onopen = () => {
        reconnectDelay = 1000;
        void fetchUnread(); // 断线期间遗漏的通知在重连时对账。
        heartbeatTimer = setInterval(() => {
          if (currentSocket.readyState === WebSocket.OPEN) currentSocket.send('ping');
        }, 25000);
      };
      currentSocket.onmessage = (message) => {
        if (typeof message.data !== 'string') return;
        try {
          const update = JSON.parse(message.data) as { event?: string; unreadCount?: number | string };
          if (update.event !== 'notification') return;
          const count = Number(update.unreadCount);
          if (!Number.isSafeInteger(count) || count < 0) return;
          // WebSocket 直接携带权威未读数；失联期间由重连和定时查询补齐。
          unreadRequestRef.current += 1;
          setUnread(count);
          void fetchUnread(); // 并发提交可能让 WebSocket 包乱序；随后以数据库最新值对账。
          if (showListRef.current) void fetchList();
        } catch { /* 忽略 pong 或非 JSON 同步消息 */ }
      };
      currentSocket.onclose = () => {
        if (heartbeatTimer) clearInterval(heartbeatTimer);
        heartbeatTimer = null;
        if (!stopped) {
          reconnectTimer = setTimeout(connect, reconnectDelay);
          reconnectDelay = Math.min(reconnectDelay * 2, 30000);
        }
      };
      currentSocket.onerror = () => currentSocket.close();
    };

    connect();
    return () => {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      socket?.close();
    };
  }, [userId, fetchUnread, fetchList]);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setShowList(false); };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  useEffect(() => {
    if (!showList) return;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      setShowList(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [showList]);

  useLayoutEffect(() => {
    if (!showList) return;
    const updateMobilePanelPosition = () => {
      if (window.innerWidth >= 640 || !triggerRef.current) {
        setMobilePanelStyle(undefined);
        return;
      }
      const top = triggerRef.current.getBoundingClientRect().bottom + 8;
      setMobilePanelStyle({
        top,
        maxHeight: Math.max(0, window.innerHeight - top - 12),
      });
    };
    updateMobilePanelPosition();
    window.addEventListener('resize', updateMobilePanelPosition);
    window.addEventListener('scroll', updateMobilePanelPosition, true);
    return () => {
      window.removeEventListener('resize', updateMobilePanelPosition);
      window.removeEventListener('scroll', updateMobilePanelPosition, true);
    };
  }, [showList]);

  const handleOpen = () => {
    if (!showList) { void fetchUnread(); void fetchList(); }
    setShowList(!showList);
  };

  const handleClickNotif = async (n: NotificationItem) => {
    // 通知目标查询可能乱序返回；只有最后一次点击能触发提示或跳转。
    const selection = ++selectionRef.current;
    if (n.read === 0) {
      try {
        await api.put(`/notification/${n.id}/read`);
        setUnread(prev => Math.max(0, prev - 1));
        setNotifications(prev => prev.map(item => item.id === n.id ? { ...item, read: 1 } : item));
      } catch {
        // 已读失败时保留服务端真实未读计数，避免前端把未读通知伪装成已读。
        if (selection === selectionRef.current) showToast('通知已保留为未读', 'warning');
      }
    }
    if (selection !== selectionRef.current) return;
    setShowList(false);
    // 新 FILE_CHANGE 必须通过服务端目标接口实时核权，不能信任通知中存储的 URL/路径。
    // 只有带 eventId 的通知属于新的关注变更链路；旧 FILE_CHANGE 即使历史数据
    // 带有 nodeId，也必须继续沿用原通知行为，不能被误送到新 target 接口。
    if (n.type === 'FILE_CHANGE' && n.eventId) {
      try {
        const target = await api.get<NotificationTarget>(`/notification/${encodeURIComponent(n.id)}/target`);
        if (selection !== selectionRef.current) return;
        const url = buildTargetUrl(target);
        if (!url) {
          showToast('关注内容已不可用', 'info');
          return;
        }
        navigate(url);
      } catch {
        if (selection === selectionRef.current) showToast('通知目标暂时无法访问', 'error');
      }
      return;
    }
    // 旧团队通知保持原有跳转行为；旧 FILE_CHANGE 没有新 eventId 时不猜测文件目标。
    if (n.refType === 'team' && n.refId) navigate(`/team/${encodeURIComponent(n.refId)}`);
  };

  const handleMarkAll = async () => {
    if (markingAll) return;
    setMarkingAll(true);
    try {
      await api.put('/notification/read-all');
      setUnread(0);
      setNotifications(prev => prev.map(n => ({ ...n, read: 1 })));
    } catch {
      showToast('标记已读失败，请重试', 'error');
    } finally {
      setMarkingAll(false);
    }
  };

  return (
    <div className="relative" ref={ref}>
      <button ref={triggerRef} onClick={handleOpen} className={cn('relative inline-flex h-9 w-9 items-center justify-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring cursor-pointer', showList && 'bg-primary-500/10 text-primary-600')} aria-label={unread > 0 ? `通知，${unread} 条未读` : '通知，无未读消息'} aria-haspopup="dialog" aria-expanded={showList} aria-controls="notification-panel">
        <Bell className="h-5 w-5" aria-hidden />
        {unread > 0 && <span className="absolute -right-1 -top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full border-2 border-surface bg-danger px-0.5 text-[10px] font-semibold leading-none text-white tabular-nums">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {showList && (
        <div
          id="notification-panel"
          className="fixed inset-x-3 z-50 flex flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-float sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-3 sm:w-[23rem]"
          style={mobilePanelStyle}
          role="dialog"
          aria-label="通知"
        >
          <div className="flex items-start justify-between gap-3 px-4 pb-3 pt-4">
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-primary-500/10 text-primary-600"><BellRing className="h-5 w-5" aria-hidden /></div>
              <div>
                <h2 className="text-base font-semibold text-fg">通知</h2>
                <p className="mt-0.5 text-xs text-muted">最近收到的消息</p>
              </div>
            </div>
            <button type="button" onClick={() => { setShowList(false); triggerRef.current?.focus(); }} className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg text-muted transition-colors hover:bg-surface-2 hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label="关闭通知"><X className="h-4 w-4" aria-hidden /></button>
          </div>
          <div className="flex items-center justify-between gap-3 border-y border-border bg-surface-2/60 px-4 py-2.5">
            <span className="text-xs text-muted">{unread > 0 ? <><strong className="font-semibold tabular-nums text-primary-600">{unread}</strong> 条未读</> : '已读完所有消息'}</span>
            {unread > 0 && <button type="button" onClick={() => void handleMarkAll()} disabled={markingAll} className="rounded-md px-2 py-1 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50">{markingAll ? '处理中…' : '全部标记已读'}</button>}
          </div>
          <div className="min-h-0 flex-1 overflow-auto sm:max-h-96 sm:flex-none">
            {isListLoading ? (
              <div className="divide-y divide-border-light" role="status" aria-label="正在加载通知">
                {Array.from({ length: 3 }).map((_, index) => <div key={index} className="flex gap-3 px-4 py-4"><div className="h-9 w-9 flex-shrink-0 rounded-xl bg-surface-2 shimmer" /><div className="flex-1 space-y-2"><div className="h-3 w-2/5 rounded bg-surface-2 shimmer" /><div className="h-3 w-4/5 rounded bg-surface-2 shimmer" /></div></div>)}
              </div>
            ) : listError ? (
              <div className="px-6 py-10 text-center" role="alert">
                <p className="text-sm font-medium text-fg">通知加载失败</p>
                <p className="mt-1 text-xs text-muted">请检查网络后重试</p>
                <button type="button" onClick={() => void fetchList()} className="btn-secondary mt-4 h-8 px-3 text-xs">重新加载</button>
              </div>
            ) : notifications.length === 0 ? (
              <div className="flex flex-col items-center px-6 py-12 text-center">
                <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-surface-2 text-muted"><Inbox className="h-7 w-7" strokeWidth={1.5} aria-hidden /></div>
                <p className="text-sm font-semibold text-fg">暂时没有通知</p>
                <p className="mt-1 text-xs text-muted">关注内容更新后，消息会显示在这里</p>
              </div>
            ) : (
              <div className="divide-y divide-border-light">
                {notifications.map(n => {
                  const Icon = iconMap[n.type] || Bell;
                  return (
                    <button type="button" key={n.id} onClick={() => void handleClickNotif(n)} className={cn('group flex w-full items-start gap-3 border-l-2 border-l-transparent px-4 py-3.5 text-left transition-colors hover:bg-bg-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring', n.read === 0 && 'border-l-primary-500 bg-primary-500/5')} aria-label={`${n.title}${n.read === 0 ? '，未读' : ''}`}>
                      <div className={cn('flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl', iconTones[n.type] || 'bg-surface-2 text-muted')}><Icon className="h-[18px] w-[18px]" strokeWidth={1.8} aria-hidden /></div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-[11px] font-medium text-muted">{typeLabels[n.type] || '通知'}</span>
                          <span className="flex-shrink-0 text-[11px] text-tertiary">{timeAgo(n.createdAt)}</span>
                        </div>
                        <p className={cn('mt-1 text-sm leading-5', n.read === 0 ? 'font-semibold text-fg' : 'font-medium text-muted')}>{n.title}</p>
                        {n.content && <p className="mt-1 line-clamp-2 text-xs leading-5 text-muted">{n.content}</p>}
                      </div>
                      {n.read === 0 && <span className="mt-1.5 h-2 w-2 flex-shrink-0 rounded-full bg-primary-500" aria-hidden />}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
          <div className="border-t border-border bg-surface px-4 py-2.5">
            <button type="button" onClick={() => { setShowList(false); navigate('/following'); }} className="flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-xs font-medium text-muted transition-colors hover:bg-surface-2 hover:text-primary-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <span>管理我的关注</span><ChevronRight className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
