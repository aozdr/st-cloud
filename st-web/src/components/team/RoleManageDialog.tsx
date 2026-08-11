import { useState, useEffect, useCallback } from 'react';
import { X, Plus, Pencil, Trash2, Ban, CheckCircle } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { cn } from '../../lib/utils';
import type { TeamRoleInfo } from '../../types';

interface RoleManageDialogProps { spaceId: string; onClose: () => void; }

const PERMISSION_KEYS = [
  { key: 'view', label: '查看文件' }, { key: 'download', label: '下载文件' },
  { key: 'upload', label: '上传文件' }, { key: 'delete', label: '删除文件' },
  { key: 'rename', label: '重命名' }, { key: 'move', label: '移动' },
  { key: 'share', label: '分享' }, { key: 'manage_members', label: '管理成员' },
  { key: 'manage_settings', label: '管理设置' },
];

export default function RoleManageDialog({ spaceId, onClose }: RoleManageDialogProps) {
  const { showToast } = useToast();
  const [roles, setRoles] = useState<TeamRoleInfo[]>([]);
  const [editing, setEditing] = useState<TeamRoleInfo | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState('');
  const [formPerms, setFormPerms] = useState<Record<string, boolean>>({});

  const fetchRoles = useCallback(async () => {
    try { const res = await api.get<TeamRoleInfo[]>(`/team/${spaceId}/roles`); setRoles(res || []); } catch { /* ignore */ }
  }, [spaceId]);

  useEffect(() => { fetchRoles(); }, [fetchRoles]);

  const openCreate = () => {
    setEditing(null); setFormName(''); setFormPerms({ view: true, download: true });
    setShowForm(true);
  };

  const openEdit = (role: TeamRoleInfo) => {
    setEditing(role); setFormName(role.name);
    try { setFormPerms(JSON.parse(role.permissions)); } catch { setFormPerms({}); }
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!formName.trim()) { showToast('请输入角色名称', 'warning'); return; }
    const perms = JSON.stringify(formPerms);
    try {
      if (editing) { await api.put(`/team/${spaceId}/role/${editing.id}`, { name: formName.trim(), permissions: perms }); showToast('角色已更新', 'success'); }
      else { await api.post(`/team/${spaceId}/role`, { name: formName.trim(), permissions: perms }); showToast('角色已创建', 'success'); }
      setShowForm(false); fetchRoles();
    } catch (e) { showToast('操作失败', 'error'); }
  };

  const handleDelete = async (roleId: string) => {
    if (!confirm('确定删除该角色？')) return;
    try { await api.delete(`/team/${spaceId}/role/${roleId}`); showToast('已删除', 'success'); fetchRoles(); } catch (e) { showToast('删除失败（可能角色正在使用）', 'error'); }
  };

  const togglePerm = (key: string) => setFormPerms(prev => ({ ...prev, [key]: !prev[key] }));

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
          <h2 className="text-base font-semibold text-fg">角色管理</h2>
          <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" /></button>
        </div>
        <div className="p-5 space-y-3">
          <div className="flex justify-end">
            <button onClick={openCreate} className="flex items-center gap-1 px-3 py-1.5 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 cursor-pointer"><Plus className="w-4 h-4" />新建角色</button>
          </div>
          {roles.map(role => (
            <div key={role.id} className="flex items-center justify-between px-3 py-2.5 bg-surface-2 rounded-md">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium text-fg">{role.name}</span>
                {role.isPreset && <span className="text-xs text-muted bg-surface px-1.5 py-0.5 rounded">预设</span>}
                {!role.isPreset && role.status === 0 && <span className="text-xs text-muted">已停用</span>}
              </div>
              {!role.isPreset && (
                <div className="flex items-center gap-1">
                  <button onClick={() => openEdit(role)} className="p-1 text-muted hover:text-primary-600 cursor-pointer" aria-label="编辑"><Pencil className="w-3.5 h-3.5" /></button>
                  <button onClick={() => handleDelete(role.id)} className="p-1 text-muted hover:text-red-500 cursor-pointer" aria-label="删除"><Trash2 className="w-3.5 h-3.5" /></button>
                </div>
              )}
            </div>
          ))}
        </div>
        {showForm && (
          <div className="border-t border-border p-5 space-y-3">
            <h3 className="text-sm font-medium text-fg">{editing ? '编辑角色' : '新建角色'}</h3>
            <input type="text" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="角色名称" className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400" />
            <div className="grid grid-cols-2 gap-2">
              {PERMISSION_KEYS.map(p => (
                <label key={p.key} className="flex items-center gap-2 px-3 py-2 bg-surface-2 rounded-md cursor-pointer">
                  <input type="checkbox" checked={formPerms[p.key] || false} onChange={() => togglePerm(p.key)} className="cursor-pointer" />
                  <span className="text-sm text-fg">{p.label}</span>
                </label>
              ))}
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setShowForm(false)} className="btn-secondary">取消</button>
              <button onClick={handleSave} className="btn-primary">保存</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}