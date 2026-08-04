import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Users, Plus, FolderClosed, X, HardDrive } from 'lucide-react';
import api from '../lib/api';
import { useToast } from '../components/ui/Toast';
import { formatSize } from '../lib/utils';
import type { TeamSpace, PageResult } from '../types';

export default function TeamPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [spaces, setSpaces] = useState<TeamSpace[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({ spaceName: '', description: '' });

  const fetchSpaces = useCallback(async () => {
    setLoading(true);
    try {
      const data: PageResult<TeamSpace> = await api.get('/team/spaces', { params: { page: 1, size: 50 } });
      setSpaces(data.records || []);
    } catch {
      setSpaces([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSpaces();
  }, [fetchSpaces]);

  const handleCreate = async () => {
    if (!form.spaceName.trim()) {
      showToast('请输入空间名称', 'warning');
      return;
    }
    try {
      await api.post('/team/space', { spaceName: form.spaceName, description: form.description });
      showToast('团队空间创建成功', 'success');
      setShowCreate(false);
      setForm({ spaceName: '', description: '' });
      fetchSpaces();
    } catch (e: any) {
      showToast(e.message || '创建失败', 'error');
    }
  };

  const usedPercent = (space: TeamSpace) => {
    const used = Number(space.storageUsed);
    const quota = Number(space.storageQuota);
    return quota > 0 ? Math.min(100, (used / quota) * 100) : 0;
  };

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-stone-200 bg-white">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
            <Users className="w-4 h-4 text-white" />
          </div>
          <h1 className="text-lg font-semibold text-stone-900">团队空间</h1>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-1.5 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          创建空间
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto p-6">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <div className="w-8 h-8 border-3 border-primary-600 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : spaces.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-stone-400">
            <Users className="w-12 h-12 mb-3 opacity-30" />
            <p className="text-sm">暂无团队空间</p>
            <p className="text-xs mt-1">创建团队空间，与团队成员共享文件</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {spaces.map((space) => (
              <div
                key={space.id}
                onClick={() => navigate(`/team/${space.id}`)}
                className="bg-white rounded-lg border border-stone-200 p-5 cursor-pointer hover:shadow-sm transition-all group"
              >
                <div className="flex items-start justify-between mb-4">
                  <div className="w-12 h-12 bg-primary-50 rounded-lg flex items-center justify-center">
                    <FolderClosed className="w-6 h-6 text-primary-600" />
                  </div>
                  <span className="text-xs text-stone-400">{space.memberCount} 成员</span>
                </div>
                <h3 className="text-sm font-semibold text-stone-900 mb-1 group-hover:text-primary-600 transition-colors">
                  {space.spaceName}
                </h3>
                <p className="text-xs text-stone-500 mb-3 line-clamp-2 min-h-[2rem]">
                  {space.description || '暂无描述'}
                </p>
                {/* Storage bar */}
                <div className="flex items-center gap-2 text-xs text-stone-400">
                  <HardDrive className="w-3 h-3" />
                  <div className="flex-1 h-1.5 bg-stone-200 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-primary-600 rounded-full"
                      style={{ width: `${usedPercent(space)}%` }}
                    />
                  </div>
                  <span>{formatSize(Number(space.storageUsed))}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Create space dialog */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-900/40" onClick={() => setShowCreate(false)}>
          <div className="w-full max-w-md bg-white rounded-xl shadow-lg border border-stone-200 overflow-hidden" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-stone-100">
              <h2 className="text-base font-semibold text-stone-900">创建团队空间</h2>
              <button onClick={() => setShowCreate(false)} className="text-stone-400 hover:text-stone-600 cursor-pointer">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <label className="text-xs font-medium text-stone-500 mb-1.5 block">空间名称</label>
                <input
                  type="text"
                  value={form.spaceName}
                  onChange={(e) => setForm({ ...form, spaceName: e.target.value })}
                  placeholder="如：产品研发组"
                  autoFocus
                  className="w-full px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none focus:border-primary-400 focus:bg-white transition-colors"
                />
              </div>
              <div>
                <label className="text-xs font-medium text-stone-500 mb-1.5 block">空间描述</label>
                <textarea
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  placeholder="简要描述空间用途"
                  rows={3}
                  className="w-full px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none focus:border-primary-400 focus:bg-white transition-colors resize-none"
                />
              </div>
              <button
                onClick={handleCreate}
                className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
              >
                创建空间
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
