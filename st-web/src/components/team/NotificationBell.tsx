import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Check, MessageSquare, Users, AlertTriangle, FileText } from 'lucide-react';
import api from '../../lib/api';
import { cn } from '../../lib/utils';
import type { NotificationItem, PageResult } from '../../types';

const iconMap: Record<string, typeof Bell> = {
  MENTION: MessageSquare,
  TEAM_INVITE: Users,
  MEMBER_CHANGE: AlertTriangle,
  FILE_CHANGE: FileText,
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

export default function NotificationBell() {
  const navigate = useNavigate();
  const [unread, setUnread] = useState(0);
  const [showList, setShowList] = useState(false);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const ref = useRef<HTMLDivElement>(null);

  const fetchUnread = useCallback(async () => {
    try { const count: number = await api.get('/notification/unread-count'); setUnread(count || 0); } catch { /* ignore */ }
  }, []);

  const fetchList = useCallback(async () => {
    try { const res = await api.get<PageResult<NotificationItem>>('/notification', { params: { page: 1, size: 20 } }); setNotifications(res?.records || []); } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    fetchUnread();
    const timer = setInterval(fetchUnread, 30000);
    return () => clearInterval(timer);
  }, [fetchUnread]);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setShowList(false); };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  const handleOpen = () => {
    if (!showList) { fetchList(); }
    setShowList(!showList);
  };

  const handleClickNotif = async (n: NotificationItem) => {
    if (n.read === 0) { try { await api.put(`/notification/${n.id}/read`); setUnread(prev => Math.max(0, prev - 1)); } catch { /* ignore */ } }
    setShowList(false);
    if (n.refType === 'team' && n.refId) navigate(`/team/${n.refId}`);
  };

  const handleMarkAll = async () => {
    try { await api.put('/notification/read-all'); setUnread(0); setNotifications(prev => prev.map(n => ({ ...n, read: 1 }))); } catch { /* ignore */ }
  };

  return (
    <div className="relative" ref={ref}>
      <button onClick={handleOpen} className="relative p-2 text-muted hover:text-fg cursor-pointer transition-colors" aria-label="通知">
        <Bell className="w-5 h-5" />
        {unread > 0 && <span className="absolute -top-0.5 -right-0.5 min-w-[16px] h-4 px-1 bg-red-500 text-white text-[10px] font-medium rounded-full flex items-center justify-center">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {showList && (
        <div className="absolute right-0 top-full mt-2 w-80 bg-surface rounded-lg shadow-lg border border-border overflow-hidden z-50">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-border">
            <span className="text-sm font-medium text-fg">通知</span>
            {unread > 0 && <button onClick={handleMarkAll} className="text-xs text-primary-600 hover:text-primary-700 cursor-pointer">全部已读</button>}
          </div>
          <div className="max-h-96 overflow-auto">
            {notifications.length === 0 ? (
              <div className="py-8 text-center text-sm text-muted">暂无通知</div>
            ) : notifications.map(n => {
              const Icon = iconMap[n.type] || Bell;
              return (
                <div key={n.id} onClick={() => handleClickNotif(n)} className={cn('flex items-start gap-2.5 px-4 py-3 cursor-pointer hover:bg-surface-2 transition-colors border-b border-border/50', n.read === 0 && 'bg-primary-500/5')}>
                  <div className="w-8 h-8 rounded-full bg-surface-2 flex items-center justify-center flex-shrink-0"><Icon className="w-4 h-4 text-muted" /></div>
                  <div className="min-w-0 flex-1">
                    <p className={cn('text-sm', n.read === 0 ? 'font-medium text-fg' : 'text-muted')}>{n.title}</p>
                    {n.content && <p className="text-xs text-muted mt-0.5 line-clamp-2">{n.content}</p>}
                    <p className="text-xs text-muted mt-0.5">{timeAgo(n.createdAt)}</p>
                  </div>
                  {n.read === 0 && <span className="w-2 h-2 rounded-full bg-primary-500 flex-shrink-0 mt-1.5" />}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}