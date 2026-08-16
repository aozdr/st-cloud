import { useState, useEffect, useCallback } from 'react';
import { HardDrive, FileText, Users, Activity } from 'lucide-react';
import api from '../../lib/api';
import { formatSize, cn } from '../../lib/utils';
import type { TeamStats } from '../../types';

interface StatsPanelProps { spaceId: string; }

function timeAgo(iso: string | null): string {
  if (!iso) return '从未';
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  return `${Math.floor(hour / 24)}天前`;
}

const actionLabels: Record<string, string> = {
  FILE_UPLOAD: '上传', FILE_DELETE: '删除', FILE_RENAME: '重命名', FILE_MOVE: '移动', FILE_COPY: '复制',
  FOLDER_CREATE: '建文件夹', MEMBER_JOIN: '加入', MEMBER_LEAVE: '退出', MEMBER_INVITE: '邀请',
  SPACE_UPDATE: '设置变更', FILE_LOCK: '锁定', FILE_UNLOCK: '解锁',
};

export default function StatsPanel({ spaceId }: StatsPanelProps) {
  const [stats, setStats] = useState<TeamStats | null>(null);
  const [days, setDays] = useState(7);

  const fetchStats = useCallback(async () => {
    try { const res = await api.get<TeamStats>(`/team/${spaceId}/stats`, { params: { days } }); setStats(res); } catch { /* ignore */ }
  }, [spaceId, days]);

  useEffect(() => { fetchStats(); }, [fetchStats]);

  if (!stats) return <div className="p-8 text-center text-sm text-muted">加载中...</div>;

  const usedPercent = Number(stats.storageQuota) > 0 ? Math.min(100, (Number(stats.storageUsed) / Number(stats.storageQuota)) * 100) : 0;
  const maxOpCount = Math.max(...stats.operationStats.map(o => o.count), 1);

  return (
    <div className="p-5 space-y-5">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted">时间范围：</span>
        {[7, 30, 90].map(d => (
          <button key={d} onClick={() => setDays(d)} className={cn('px-2 py-1 text-xs rounded-md cursor-pointer', days === d ? 'bg-primary-600 text-white' : 'bg-surface-2 text-muted')}>{d}天</button>
        ))}
      </div>

      {/* 存储概览 */}
      <div>
        <p className="text-xs font-medium text-muted mb-2 flex items-center gap-1"><HardDrive className="w-3.5 h-3.5" />存储概览</p>
        <div className="flex items-center gap-3">
          <span className="text-sm text-fg">{formatSize(Number(stats.storageUsed))} / {Number(stats.storageQuota) > 0 ? formatSize(Number(stats.storageQuota)) : '不限'}</span>
          <div className="flex-1 h-2 bg-surface-2 rounded-full overflow-hidden"><div className={cn('h-full rounded-full', usedPercent > 90 ? 'bg-red-500' : usedPercent > 70 ? 'bg-amber-500' : 'bg-primary-500')} style={{ width: `${usedPercent}%` }} /></div>
          <span className="text-xs text-muted">{Math.round(usedPercent)}%</span>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        {/* 文件类型分布 */}
        <div>
          <p className="text-xs font-medium text-muted mb-2 flex items-center gap-1"><FileText className="w-3.5 h-3.5" />文件类型分布（共 {stats.fileCount} 个）</p>
          <div className="space-y-1.5">
            {stats.fileTypeDistribution.length === 0 ? <p className="text-xs text-muted">暂无文件</p> : stats.fileTypeDistribution.map(t => (
              <div key={t.type} className="flex items-center gap-2">
                <span className="text-xs text-fg w-12">{t.type}</span>
                <div className="flex-1 h-3 bg-surface-2 rounded-full overflow-hidden"><div className="h-full bg-primary-400 rounded-full" style={{ width: `${(t.count / Number(stats.fileCount)) * 100}%` }} /></div>
                <span className="text-xs text-muted w-8 text-right">{t.count}</span>
              </div>
            ))}
          </div>
        </div>

        {/* 操作统计 */}
        <div>
          <p className="text-xs font-medium text-muted mb-2 flex items-center gap-1"><Activity className="w-3.5 h-3.5" />近期操作统计</p>
          <div className="space-y-1">
            {stats.operationStats.length === 0 ? <p className="text-xs text-muted">暂无操作</p> : stats.operationStats.slice(0, 6).map(op => (
              <div key={op.action} className="flex items-center gap-2">
                <span className="text-xs text-fg w-12">{actionLabels[op.action] || op.action}</span>
                <div className="flex-1 h-3 bg-surface-2 rounded-full overflow-hidden"><div className="h-full bg-primary-500 rounded-full" style={{ width: `${(op.count / maxOpCount) * 100}%` }} /></div>
                <span className="text-xs text-muted w-8 text-right">{op.count}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 成员活跃度 */}
      <div>
        <p className="text-xs font-medium text-muted mb-2 flex items-center gap-1"><Users className="w-3.5 h-3.5" />成员活跃度排行</p>
        <div className="space-y-1.5">
          {stats.memberActivity.map((m, i) => (
            <div key={m.userId} className="flex items-center gap-2">
              <span className="text-xs text-muted w-4">{i + 1}.</span>
              <div className="w-6 h-6 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs flex-shrink-0">{m.nickname?.[0] || '?'}</div>
              <span className="text-sm text-fg flex-1">{m.nickname}</span>
              <span className="text-xs text-muted">{timeAgo(m.lastActiveAt)}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
