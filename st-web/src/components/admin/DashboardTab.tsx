import { useState, useEffect, useCallback } from 'react';
import { Users, HardDrive, FileText, Share2, Activity } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { formatSize } from '../../lib/utils';
import { useStorageStore } from '../../store/storage';
import type { StatsVO } from '../../types';
import { CloudCapacityEditDialog } from './AdminDialogs';

export default function DashboardTab() {
  const { showToast } = useToast();
  const [stats, setStats] = useState<StatsVO | null>(null);
  const [cloudCapacityOpen, setCloudCapacityOpen] = useState(false);

  const fetchStats = useCallback(async () => {
    try {
      const data: StatsVO = await api.get('/admin/stats');
      setStats(data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { fetchStats(); }, [fetchStats]);

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

  const statCards = stats ? [
    { label: '总用户数', value: stats.totalUsers, icon: Users },
    { label: '活跃用户(7天)', value: stats.activeUsers, icon: Activity },
    { label: '总文件数', value: stats.totalFiles, icon: FileText },
    { label: '存储用量', value: formatSize(stats.totalStorageUsed), icon: HardDrive },
    { label: '分享总数', value: stats.totalShares, icon: Share2 },
    { label: '团队空间', value: stats.totalTeams, icon: Users },
  ] : [];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        {statCards.map((card) => (
          <div key={card.label} className="bg-surface rounded-lg border border-border p-4">
            <div className="w-10 h-10 bg-primary-500/10 rounded-lg flex items-center justify-center mb-3">
              <card.icon className="w-5 h-5 text-primary-600" aria-hidden />
            </div>
            <p className="text-2xl font-bold text-fg">{card.value}</p>
            <p className="text-xs text-muted mt-0.5">{card.label}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-surface rounded-lg border border-border p-5">
          <h3 className="text-sm font-semibold text-muted mb-4">存储用量概览</h3>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={statCards.slice(0, 3).map(c => ({ name: c.label, value: c.value }))}>
              <XAxis dataKey="name" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} />
              <Tooltip />
              <Bar dataKey="value" fill="#306EFF" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="bg-surface rounded-lg border border-border p-5">
          <h3 className="text-sm font-semibold text-muted mb-4">用户活跃度</h3>
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie
                data={[
                  { name: '活跃用户', value: stats?.activeUsers || 0 },
                  { name: '非活跃用户', value: (stats?.totalUsers || 0) - (stats?.activeUsers || 0) },
                ]}
                cx="50%" cy="50%" innerRadius={50} outerRadius={80}
                dataKey="value"
                label={({ name, value }) => `${name}: ${value}`}
              >
                <Cell fill="#22c55e" />
                <Cell fill="#e2e8f0" />
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>

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
