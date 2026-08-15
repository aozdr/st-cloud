import { useState, useEffect } from 'react';
import api from '../lib/api';
import { formatSize, cn } from '../lib/utils';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';

interface TypeStat {
  type: string;
  label: string;
  size: number;
  color: string;
}

const TYPE_CONFIG: Record<string, { label: string; color: string }> = {
  image: { label: '图片', color: '#3b82f6' },
  video: { label: '视频', color: '#a855f7' },
  document: { label: '文档', color: '#f59e0b' },
  audio: { label: '音乐', color: '#ec4899' },
  archive: { label: '压缩包', color: '#10b981' },
  other: { label: '其他', color: '#64748b' },
};

/**
 * 存储空间分析组件：按文件类型展示存储占用分布
 */
export default function StorageAnalysis() {
  const [stats, setStats] = useState<TypeStat[]>([]);
  const [loading, setLoading] = useState(true);
  const [totalSize, setTotalSize] = useState(0);

  useEffect(() => {
    api.get<{ type: string; size: number }[]>('/file/storage/by-type')
      .then((data) => {
        const mapped: TypeStat[] = data.map((item) => ({
          type: item.type,
          label: TYPE_CONFIG[item.type]?.label || item.type,
          size: Number(item.size),
          color: TYPE_CONFIG[item.type]?.color || '#64748b',
        }));
        const total = mapped.reduce((sum, s) => sum + s.size, 0);
        setStats(mapped);
        setTotalSize(total);
      })
      .catch(() => {
        // 接口不可用时不展示，不影响首页
        setStats([]);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading || stats.length === 0) return null;

  return (
    <section>
      <div className="flex items-center gap-2 mb-4">
        <h2 className="text-base font-semibold text-fg">存储空间分析</h2>
        <span className="text-xs text-muted tabular-nums">{formatSize(totalSize)}</span>
      </div>
      <div className="flex items-center gap-6 bg-surface rounded-xl border border-border p-5">
        {/* 饼图 */}
        <div className="w-32 h-32 flex-shrink-0">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={stats}
                dataKey="size"
                nameKey="label"
                cx="50%"
                cy="50%"
                innerRadius={36}
                outerRadius={60}
                paddingAngle={2}
              >
                {stats.map((entry, idx) => (
                  <Cell key={idx} fill={entry.color} />
                ))}
              </Pie>
              <Tooltip
                formatter={(value: number) => formatSize(value)}
                contentStyle={{ fontSize: '12px', borderRadius: '8px', border: '1px solid rgb(var(--color-border))' }}
              />
            </PieChart>
          </ResponsiveContainer>
        </div>
        {/* 图例列表 */}
        <div className="flex-1 grid grid-cols-2 gap-2">
          {stats.sort((a, b) => b.size - a.size).map((stat) => {
            const pct = totalSize > 0 ? (stat.size / totalSize) * 100 : 0;
            return (
              <div key={stat.type} className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-sm flex-shrink-0" style={{ backgroundColor: stat.color }} />
                <span className="text-sm text-fg flex-1 truncate">{stat.label}</span>
                <span className="text-xs text-muted tabular-nums">{formatSize(stat.size)}</span>
                <span className="text-xs text-muted tabular-nums w-10 text-right">{pct.toFixed(1)}%</span>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
