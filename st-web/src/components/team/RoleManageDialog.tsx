import { useState, useEffect, useCallback, useRef } from 'react';
import {
  X, Plus, Pencil, Trash2, Shield,
  Eye, Download, Upload, PencilLine, Move, Share2, Users, Settings,
  type LucideIcon,
} from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { cn } from '../../lib/utils';
import type { TeamRoleInfo } from '../../types';
import { PERMISSION_KEYS } from '../../lib/permissions';

interface RoleManageDialogProps { spaceId: string; onClose: () => void; }

/** 权限点 key → 图标（9 个权限点与 PERMISSION_KEYS 一一对应） */
const PERMISSION_ICONS: Record<string, LucideIcon> = {
  view: Eye,
  download: Download,
  upload: Upload,
  delete: Trash2,
  rename: PencilLine,
  move: Move,
  share: Share2,
  manage_members: Users,
  manage_settings: Settings,
};

/** 权限点 key → 展示名（复用单源 PERMISSION_KEYS，避免 label 漂移） */
const PERMISSION_LABELS: Record<string, string> = PERMISSION_KEYS.reduce<Record<string, string>>((acc, p) => {
  acc[p.key] = p.label;
  return acc;
}, {});

/** 权限点分组：文件访问 / 文件操作 / 协作分享 / 管理（9 个权限点全覆盖） */
const PERMISSION_GROUPS: { title: string; icon: LucideIcon; keys: string[] }[] = [
  { title: '文件访问', icon: Eye, keys: ['view', 'download', 'upload'] },
  { title: '文件操作', icon: PencilLine, keys: ['delete', 'rename', 'move'] },
  { title: '协作分享', icon: Share2, keys: ['share'] },
  { title: '管理', icon: Users, keys: ['manage_members', 'manage_settings'] },
];

/** 防御式解析权限点：后端返回字符串 JSON，前端也可能直接拿到对象 */
function parsePermissions(raw: unknown): Record<string, boolean> {
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const obj = JSON.parse(raw);
      return obj && typeof obj === 'object' ? (obj as Record<string, boolean>) : {};
    } catch { return {}; }
  }
  if (raw && typeof raw === 'object') return raw as Record<string, boolean>;
  return {};
}

export default function RoleManageDialog({ spaceId, onClose }: RoleManageDialogProps) {
  const { showToast } = useToast();
  const [roles, setRoles] = useState<TeamRoleInfo[]>([]);
  const [editing, setEditing] = useState<TeamRoleInfo | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState('');
  const [formPerms, setFormPerms] = useState<Record<string, boolean>>({});
  const nameRef = useRef<HTMLInputElement>(null);

  const fetchRoles = useCallback(async () => {
    try { const res = await api.get<TeamRoleInfo[]>(`/team/${spaceId}/roles`); setRoles(res || []); } catch { /* ignore */ }
  }, [spaceId]);

  useEffect(() => { fetchRoles(); }, [fetchRoles]);

  // Esc 关闭对话框
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  // 表单打开时自动聚焦角色名称输入框
  useEffect(() => {
    if (showForm) nameRef.current?.focus();
  }, [showForm]);

  /** 新建：默认勾选查看 + 下载（与既有行为一致） */
  const openCreate = () => {
    setEditing(null); setFormName(''); setFormPerms({ view: true, download: true });
    setShowForm(true);
  };

  /** 编辑：回填名称与权限（防御式解析，坏 JSON 回退空集） */
  const openEdit = (role: TeamRoleInfo) => {
    setEditing(role); setFormName(role.name);
    setFormPerms(parsePermissions(role.permissions));
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!formName.trim()) { showToast('请输入角色名称', 'warning'); return; }
    // upload/download 隐含 view：保存前归一化，与后端隐含规则保持一致
    const finalPerms = { ...formPerms, view: Boolean(formPerms.view || formPerms.upload || formPerms.download) };
    const perms = JSON.stringify(finalPerms);
    try {
      if (editing) { await api.put(`/team/${spaceId}/role/${editing.id}`, { name: formName.trim(), permissions: perms }); showToast('角色已更新', 'success'); }
      else { await api.post(`/team/${spaceId}/role`, { name: formName.trim(), permissions: perms }); showToast('角色已创建', 'success'); }
      setShowForm(false); setEditing(null); fetchRoles();
    } catch { showToast('操作失败', 'error'); }
  };

  const handleDelete = async (roleId: string) => {
    if (!confirm('确定删除该角色？')) return;
    try {
      await api.delete(`/team/${spaceId}/role/${roleId}`);
      showToast('已删除', 'success');
      // 删除的是当前编辑中的角色时，关闭表单避免残留状态
      if (editing?.id === roleId) { setEditing(null); setShowForm(false); }
      fetchRoles();
    } catch { showToast('删除失败（可能角色正在使用）', 'error'); }
  };

  /** 权限勾选联动：勾选 upload/download 自动补 view；取消 view 联动取消 upload/download（与后端隐含规则一致） */
  const togglePerm = (key: string) => setFormPerms(prev => {
    const next = { ...prev, [key]: !prev[key] };
    if ((key === 'upload' || key === 'download') && next[key]) next.view = true;
    if (key === 'view' && !next[key]) { next.upload = false; next.download = false; }
    return next;
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="角色管理" className="w-full max-w-3xl bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] flex flex-col" onClick={(e) => e.stopPropagation()}>
        {/* 头部 */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
          <div className="flex items-center gap-2">
            <Shield className="w-4 h-4 text-primary-600" />
            <h2 className="text-base font-semibold text-fg">角色管理</h2>
          </div>
          <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer p-1 rounded-md" aria-label="关闭"><X className="w-5 h-5" /></button>
        </div>

        {/* 左：角色列表 / 右：新建·编辑表单（分区布局） */}
        <div className="flex flex-col md:flex-row flex-1 min-h-0">
          <aside className="border-b md:border-b-0 md:border-r border-border flex flex-col shrink-0 max-h-56 md:max-h-none md:shrink md:w-64 lg:w-72">
            <div className="flex items-center justify-between px-4 pt-4 pb-2">
              <p className="text-xs font-medium text-muted">角色列表</p>
              <span className="text-xs text-muted bg-surface-2 px-1.5 py-0.5 rounded">{roles.length}</span>
            </div>
            <div className="px-3 pb-2">
              <button onClick={openCreate} className="w-full flex items-center justify-center gap-1 px-3 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 cursor-pointer"><Plus className="w-4 h-4" />新建角色</button>
            </div>
            <div className="px-3 pb-3 flex-1 min-h-0 overflow-y-auto space-y-1">
              {roles.length === 0 ? (
                <p className="text-sm text-muted text-center py-6">暂无角色，点击上方「新建角色」</p>
              ) : roles.map(role => {
                const active = editing?.id === role.id;
                return (
                  <div
                    key={role.id}
                    onClick={() => !role.isPreset && openEdit(role)}
                    className={cn(
                      'flex items-center justify-between gap-2 px-3 py-2.5 rounded-md transition-colors',
                      !role.isPreset ? 'cursor-pointer hover:bg-surface-2' : 'cursor-default',
                      active ? 'bg-primary-500/10 ring-1 ring-primary-400/40' : 'bg-surface-2',
                    )}
                  >
                    <span className="flex items-center gap-2 min-w-0">
                      <span className="truncate text-sm font-medium text-fg">{role.name}</span>
                      {role.isPreset && <span className="shrink-0 text-[11px] text-muted bg-surface px-1.5 py-0.5 rounded">预设</span>}
                      {!role.isPreset && role.status === 0 && <span className="shrink-0 text-[11px] text-muted bg-surface px-1.5 py-0.5 rounded">已停用</span>}
                    </span>
                    {!role.isPreset && (
                      <span className="flex items-center gap-0.5 shrink-0" onClick={(e) => e.stopPropagation()}>
                        <button onClick={() => openEdit(role)} className="p-1 text-muted hover:text-primary-600 cursor-pointer" aria-label={`编辑${role.name}`}><Pencil className="w-3.5 h-3.5" /></button>
                        <button onClick={() => handleDelete(role.id)} className="p-1 text-muted hover:text-red-500 cursor-pointer" aria-label={`删除${role.name}`}><Trash2 className="w-3.5 h-3.5" /></button>
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          </aside>

          <section className="flex-1 min-w-0 min-h-0 overflow-y-auto p-5">
            {showForm ? (
              <div className="flex flex-col h-full">
                <div className="flex items-center justify-between mb-4">
                  <div className="flex items-center gap-2">
                    <Pencil className="w-4 h-4 text-primary-600" />
                    <h3 className="text-sm font-semibold text-fg">{editing ? '编辑角色' : '新建角色'}</h3>
                  </div>
                  {editing && (
                    <button onClick={() => handleDelete(editing.id)} className="flex items-center gap-1 px-2.5 py-1.5 text-xs text-red-500 hover:bg-red-500/10 rounded-md cursor-pointer"><Trash2 className="w-3.5 h-3.5" />删除</button>
                  )}
                </div>

                <div className="space-y-4">
                  <div>
                    <label htmlFor="role-name" className="block text-xs font-medium text-muted mb-1.5">角色名称</label>
                    <input
                      id="role-name"
                      ref={nameRef}
                      type="text"
                      value={formName}
                      onChange={(e) => setFormName(e.target.value)}
                      placeholder="请输入角色名称"
                      className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400"
                    />
                  </div>

                  {/* 9 权限点分组勾选：文件访问 / 文件操作 / 协作分享 / 管理 */}
                  <div>
                    <p className="text-xs font-medium text-muted mb-1.5">权限点</p>
                    <div className="space-y-3">
                      {PERMISSION_GROUPS.map(group => {
                        const GroupIcon = group.icon;
                        return (
                          <div key={group.title}>
                            <p className="flex items-center gap-1.5 text-xs font-medium text-muted mb-1.5"><GroupIcon className="w-3.5 h-3.5" />{group.title}</p>
                            <div className="grid grid-cols-2 gap-1.5">
                              {group.keys.map(key => {
                                const Icon = PERMISSION_ICONS[key];
                                const checked = Boolean(formPerms[key]);
                                return (
                                  <label key={key} className={cn('flex items-center gap-2 px-2.5 py-2 rounded-md border cursor-pointer transition-colors', checked ? 'bg-primary-500/10 border-primary-400/40' : 'bg-surface-2 border-border')}>
                                    <input type="checkbox" className="accent-primary-600 cursor-pointer" checked={checked} onChange={() => togglePerm(key)} />
                                    <Icon className={cn('w-3.5 h-3.5 shrink-0', checked ? 'text-primary-600' : 'text-muted')} />
                                    <span className="text-sm text-fg">{PERMISSION_LABELS[key]}</span>
                                  </label>
                                );
                              })}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  <p className="text-xs text-muted bg-surface-2/60 rounded-md px-3 py-2">勾选「上传文件」或「下载文件」将自动包含「查看文件」；取消「查看文件」会同步取消「上传/下载」（与后端规则一致）。</p>

                  <div className="flex justify-end gap-2 pt-1">
                    <button onClick={() => { setShowForm(false); setEditing(null); }} className="btn-secondary">取消</button>
                    <button onClick={handleSave} className="btn-primary">保存</button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="h-full min-h-48 flex flex-col items-center justify-center text-center gap-2">
                <Shield className="w-10 h-10 text-muted/40" />
                <p className="text-sm font-medium text-fg">管理空间角色与权限</p>
                <p className="text-xs text-muted max-w-56">从左侧选择角色进行编辑，或点击「新建角色」创建自定义角色。</p>
              </div>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
