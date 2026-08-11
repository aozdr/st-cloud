import { useState, useEffect, useCallback } from 'react';
import { HardDrive, Users, FileText, Edit3 } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { formatSize, cn } from '../../lib/utils';
import { useStorageStore } from '../../store/storage';
import type { StatsVO, AdminUser, PageResult } from '../../types';
import { QuotaEditDialog, CloudCapacityEditDialog } from './AdminDialogs';

export default function StorageManageTab() {
  const { showToast } = useToast();
  const [stats, setStats] = useState<StatsVO | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [quotaTarget, setQuotaTarget] = useState<AdminUser | null>(null);
  const [cloudCapacityOpen, setCloudCapacityOpen] = useState(false);

  const fetchStats = useCallback(async () => {
    try {
      const data: StatsVO = await api.get('/admin/stats');
      setStats(data);
    } catch { /* ignore */ }
  }, []);

  const fetchUsers = useCallback(async () => {
    try {
      const data: PageResult<AdminUser> = await api.get('/admin/user/list', { params: { page: 1, size: 100 } });
      setUsers(data.records || []);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { fetchStats(); fetchUsers(); }, [fetchStats, fetchUsers]);

  const handleUpdateQuota = async (userId: string, quotaBytes: number) => {
    try {
      await api.put(`/admin/user/${userId}`, { storageQuota: quotaBytes });
      showToast('配额已更新', 'success');
      setQuotaTarget(null);
      fetchUsers();
      useStorageStore.getState().fetchStorage();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleSaveCloudCapacity = async (capacityBytes: number) => {
    try {
      await api.put('/admin/cloud-capacity', { capacity: capacityBytes || null });
      showToast('云盘总容量已更新', 'success');
      setCloudCapacityOpen(false);
      fetchStats();
      useStorageStore.getState().fetchStorage();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  return (
    <div className="space-y-4">
      <div className="bg-surface rounded-lg border border-border p-5">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <HardDrive className="w-4 h-4 text-primary-600" />
            <span className="text-sm font-medium text-muted">云盘总容量</span>
          </div>
          <button onClick={() => setCloudCapacityOpen(true)} className="text-xs text-primary-600 hover:underline cursor-pointer">编辑</button>
        </div>
        {(() => {
          const used = stats?.cloudStorageUsed || 0;
          const total = stats?.cloudTotalCapacity;
          const pct = total && total > 0 ? Math.min((used / total) * 100, 100) : 0;
          const low = !!(total && total > 0 && pct > 90);
          const barClass = "h-full rounded-full transition-[width,background-color] " + (low ? "bg-red-500" : "bg-primary-600");
          return (
            <>
              <div className="w-full h-2 bg-surface-2 rounded-full overflow-hidden mb-2">
                <div className={barClass} style={{ width: pct + "%" }} />
              </div>
              <div className="flex justify-between text-xs text-muted">
                <span>{formatSize(used)} 已用</span>
                <span>{total && total > 0 ? formatSize(total) + " 总量" : "不限"}</span>
              </div>
              {total && total > 0 && pct > 90 && (
                <p className="mt-1.5 text-xs text-red-500 font-medium">云盘空间不足，请扩容或清理文件</p>
              )}
            </>
          );
        })()}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-surface rounded-lg border border-border p-4">
          <div className="flex items-center gap-2 mb-2">
            <HardDrive className="w-4 h-4 text-primary-600" />
            <span className="text-xs font-medium text-muted">总存储用量</span>
          </div>
          <p className="text-2xl font-bold text-fg">{formatSize(stats?.totalStorageUsed || 0)}</p>
        </div>
        <div className="bg-surface rounded-lg border border-border p-4">
          <div className="flex items-center gap-2 mb-2">
            <Users className="w-4 h-4 text-blue-500" />
            <span className="text-xs font-medium text-muted">用户数</span>
          </div>
          <p className="text-2xl font-bold text-fg">{users.length}</p>
        </div>
        <div className="bg-surface rounded-lg border border-border p-4">
          <div className="flex items-center gap-2 mb-2">
            <FileText className="w-4 h-4 text-green-500" />
            <span className="text-xs font-medium text-muted">总文件数</span>
          </div>
          <p className="text-2xl font-bold text-fg">{stats?.totalFiles || 0}</p>
        </div>
      </div>

      <Card>
        <div className="px-4 py-3 border-b border-border">
          <h3 className="text-sm font-semibold text-muted">用户存储配额管理</h3>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>用户</TableHead>
              <TableHead className="w-[280px]">存储用量</TableHead>
              <TableHead className="w-[140px]">使用率</TableHead>
              <TableHead className="w-[140px]">配额</TableHead>
              <TableHead className="text-center w-[80px]">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.map((user) => {
              const used = Number(user.storageUsed || 0);
              const quota = Number(user.storageQuota || 0);
              const pct = quota > 0 ? Math.min((used / quota) * 100, 100) : 0;
              const isUnlimited = quota === 0;
              const isHigh = pct > 90;
              return (
                <TableRow key={user.id}>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <div className="w-7 h-7 rounded-full bg-surface-2 flex items-center justify-center text-xs font-medium text-muted">
                        {(user.nickname || user.username || '?')[0].toUpperCase()}
                      </div>
                      <div>
                        <div className="text-sm font-medium text-fg">{user.username}</div>
                        {user.nickname && <div className="text-xs text-muted">{user.nickname}</div>}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="text-muted font-medium tabular-nums">{formatSize(used)}</span>
                        <span className="text-muted tabular-nums">{isUnlimited ? '不限制' : formatSize(quota)}</span>
                      </div>
                      <div className="w-full h-1.5 bg-surface-2 rounded-full overflow-hidden">
                        <div
                          className={cn('h-full rounded-full transition-[width,background-color]', isHigh ? 'bg-red-500' : isUnlimited ? 'bg-muted/50' : 'bg-primary-500')}
                          style={{ width: isUnlimited ? '8%' : pct + '%' }}
                        />
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    {isUnlimited ? (
                      <span className="text-xs text-muted">-</span>
                    ) : (
                      <span className={cn('text-xs font-medium tabular-nums', isHigh ? 'text-red-600 dark:text-red-400' : 'text-muted')}>
                        {pct.toFixed(1)}%
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span className="text-xs text-muted tabular-nums">{isUnlimited ? '不限制' : formatSize(quota)}</span>
                  </TableCell>
                  <TableCell className="text-center">
                    <button
                      onClick={() => setQuotaTarget(user)}
                      className="text-muted hover:text-primary-600 transition-colors cursor-pointer"
                      title="修改配额" aria-label="修改配额"
                    >
                      <Edit3 className="w-4 h-4" aria-hidden />
                    </button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        {users.length === 0 && (
          <div className="py-10 text-center text-sm text-muted">暂无用户数据</div>
        )}
      </Card>

      {quotaTarget && (
        <QuotaEditDialog
          user={quotaTarget}
          onClose={() => setQuotaTarget(null)}
          onSave={(bytes) => handleUpdateQuota(quotaTarget.id, bytes)}
        />
      )}
      {cloudCapacityOpen && (
        <CloudCapacityEditDialog
          currentCapacity={stats?.cloudTotalCapacity ?? null}
          used={stats?.cloudStorageUsed || 0}
          onClose={() => setCloudCapacityOpen(false)}
          onSave={handleSaveCloudCapacity}
        />
      )}
    </div>
  );
}