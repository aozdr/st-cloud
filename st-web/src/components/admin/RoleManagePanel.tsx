import { useState, useEffect, useCallback } from 'react';
import { Plus, Edit, Trash2, X, Shield, ChevronRight, ChevronDown } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { useConfirm } from '../ui/ConfirmDialog';
import type { RoleVO, PermissionVO } from '../../types';

interface RoleFormState {
  roleCode: string;
  roleName: string;
  description: string;
  dataScope: number;
  status: number;
}

const emptyForm: RoleFormState = {
  roleCode: '',
  roleName: '',
  description: '',
  dataScope: 1,
  status: 1,
};

const DATA_SCOPE_LABELS: Record<number, { label: string; cls: string }> = {
  1: { label: '本人', cls: 'text-muted bg-surface-2' },
  2: { label: '租户', cls: 'text-blue-600 bg-blue-500/15' },
  3: { label: '全部', cls: 'text-red-600 dark:text-red-400 bg-red-500/15' },
};

const MODULE_LABELS: Record<string, string> = {
  file: '文件',
  share: '分享',
  team: '团队',
  search: '搜索',
  admin: '管理',
  transfer: '传输',
};

export default function RoleManagePanel() {
  const { showToast } = useToast();
  const { confirm } = useConfirm();
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [permGroups, setPermGroups] = useState<Record<string, PermissionVO[]>>({});
  const [selectedPermIds, setSelectedPermIds] = useState<Set<string>>(new Set());
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<RoleFormState>(emptyForm);
  const [expandedModules, setExpandedModules] = useState<Set<string>>(new Set());

  const fetchRoles = useCallback(async () => {
    try {
      const list: RoleVO[] = await api.get('/admin/role/list');
      setRoles(list || []);
    } catch { /* ignore */ }
  }, []);

  const fetchPermGroups = useCallback(async () => {
    try {
      const grouped: Record<string, PermissionVO[]> = await api.get('/admin/permission/grouped');
      setPermGroups(grouped || {});
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    fetchRoles();
    fetchPermGroups();
  }, [fetchRoles, fetchPermGroups]);

  const togglePerm = (id: string) => {
    const next = new Set(selectedPermIds);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelectedPermIds(next);
  };

  const toggleModule = (perms: PermissionVO[]) => {
    const allSelected = perms.length > 0 && perms.every((p) => selectedPermIds.has(p.id));
    const next = new Set(selectedPermIds);
    if (allSelected) perms.forEach((p) => next.delete(p.id));
    else perms.forEach((p) => next.add(p.id));
    setSelectedPermIds(next);
  };

  const toggleModuleExpand = (mod: string) => {
    const next = new Set(expandedModules);
    if (next.has(mod)) next.delete(mod);
    else next.add(mod);
    setExpandedModules(next);
  };

  const openCreate = () => {
    setEditingId(null);
    setForm(emptyForm);
    setSelectedPermIds(new Set());
    setExpandedModules(new Set());
    setModalOpen(true);
  };

  const openEdit = (r: RoleVO) => {
    setEditingId(r.id);
    setForm({
      roleCode: r.roleCode,
      roleName: r.roleName,
      description: r.description || '',
      dataScope: r.dataScope ?? 1,
      status: r.status,
    });
    setSelectedPermIds(new Set(r.permissions?.map((p) => p.id) ?? []));
    const mods = new Set<string>();
    Object.entries(permGroups).forEach(([mod, perms]) => {
      if (perms.some((p) => r.permissions?.some((rp) => rp.id === p.id))) mods.add(mod);
    });
    setExpandedModules(mods);
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.roleCode.trim()) {
      showToast('请输入角色编码', 'error');
      return;
    }
    if (!form.roleName.trim()) {
      showToast('请输入角色名称', 'error');
      return;
    }
    const payload = {
      roleCode: form.roleCode.trim(),
      roleName: form.roleName.trim(),
      description: form.description.trim() || undefined,
      dataScope: form.dataScope,
      status: form.status,
    };
    const permIds = [...selectedPermIds].map(Number);
    try {
      let roleId: string;
      if (editingId) {
        await api.put(`/admin/role/${editingId}`, payload);
        roleId = editingId;
        showToast('角色已更新', 'success');
      } else {
        const created: RoleVO = await api.post('/admin/role', payload);
        roleId = created.id;
        showToast('角色已创建', 'success');
      }
      await api.put(`/admin/role/${roleId}/permissions`, { permissionIds: permIds });
      setModalOpen(false);
      fetchRoles();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleDelete = async (r: RoleVO) => {
    if (r.builtIn) {
      showToast('内置角色不可删除', 'error');
      return;
    }
    const ok = await confirm({
      title: '删除角色',
      message: `确定删除角色「${r.roleName}」吗？此操作不可恢复。`,
      danger: true,
      confirmText: '删除',
    });
    if (!ok) return;
    try {
      await api.delete(`/admin/role/${r.id}`);
      showToast('角色已删除', 'success');
      fetchRoles();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '删除失败', 'error');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-fg">角色管理</h2>
          <p className="text-xs text-muted mt-0.5">管理角色、数据范围与操作权限。数据范围决定可访问的数据边界，权限决定可执行的操作与可见的菜单。</p>
        </div>
        <button
          onClick={openCreate}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md transition-colors cursor-pointer"
        >
          <Plus className="w-4 h-4" /> 新建角色
        </button>
      </div>

      <div className="bg-surface rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-surface-2 border-b border-border">
            <tr>
              <th className="text-left px-4 py-3 font-medium text-muted">角色编码</th>
              <th className="text-left px-4 py-3 font-medium text-muted">角色名称</th>
              <th className="text-center px-4 py-3 font-medium text-muted">数据范围</th>
              <th className="text-center px-4 py-3 font-medium text-muted">权限</th>
              <th className="text-center px-4 py-3 font-medium text-muted">状态</th>
              <th className="text-center px-4 py-3 font-medium text-muted">类型</th>
              <th className="text-center px-4 py-3 font-medium text-muted">操作</th>
            </tr>
          </thead>
          <tbody>
            {roles.map((r) => {
              const ds = DATA_SCOPE_LABELS[r.dataScope] || DATA_SCOPE_LABELS[1];
              return (
                <tr key={r.id} className="border-b border-border last:border-0 hover:bg-surface-2/50">
                  <td className="px-4 py-3 text-muted font-mono text-xs">{r.roleCode}</td>
                  <td className="px-4 py-3 text-fg">{r.roleName}</td>
                  <td className="px-4 py-3 text-center">
                    <span className={`text-xs font-medium px-2 py-0.5 rounded ${ds.cls}`}>{ds.label}</span>
                  </td>
                  <td className="px-4 py-3 text-center text-xs text-muted">{r.permissions?.length ?? 0} 项</td>
                  <td className="px-4 py-3 text-center">
                    <span className={`text-xs px-2 py-0.5 rounded ${r.status === 1 ? 'text-green-600 dark:text-green-400 bg-green-500/15' : 'text-muted bg-surface-2'}`}>
                      {r.status === 1 ? '启用' : '禁用'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    {r.builtIn ? (
                      <span className="inline-flex items-center gap-1 text-xs text-primary-600 bg-primary-500/10 px-2 py-0.5 rounded">
                        <Shield className="w-3 h-3" />内置
                      </span>
                    ) : (
                      <span className="text-xs text-muted">自定义</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-center gap-3">
                      <button onClick={() => openEdit(r)} className="text-muted hover:text-primary-600 transition-colors cursor-pointer" title="编辑">
                        <Edit className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(r)}
                        disabled={r.builtIn}
                        className="text-muted hover:text-red-500 transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
                        title="删除"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {roles.length === 0 && <div className="py-10 text-center text-sm text-muted">暂无角色，点击「新建角色」创建</div>}
      </div>

      {modalOpen && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 overscroll-contain" role="presentation" onClick={() => setModalOpen(false)}>
          <div className="bg-surface rounded-lg w-full max-w-2xl p-6 max-h-[90vh] overflow-auto" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-fg">{editingId ? '编辑角色' : '新建角色'}</h2>
              <button onClick={() => setModalOpen(false)} aria-label="关闭" className="text-muted hover:text-fg transition-colors cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <X className="w-5 h-5" aria-hidden />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-muted mb-1">角色编码</label>
                <input
                  type="text"
                  value={form.roleCode}
                  disabled={!!editingId}
                  onChange={(e) => setForm((f) => ({ ...f, roleCode: e.target.value }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500 disabled:bg-surface-2 disabled:text-muted"
                  placeholder="如：auditor"
                />
                {editingId && <p className="text-xs text-muted mt-1">角色编码创建后不可修改</p>}
              </div>
              <div>
                <label className="block text-xs font-medium text-muted mb-1">角色名称</label>
                <input
                  type="text"
                  value={form.roleName}
                  onChange={(e) => setForm((f) => ({ ...f, roleName: e.target.value }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                  placeholder="如：审计员"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-muted mb-1">数据范围</label>
                <select
                  value={form.dataScope}
                  onChange={(e) => setForm((f) => ({ ...f, dataScope: Number(e.target.value) }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                >
                  <option value={1}>本人（仅访问自有资源）</option>
                  <option value={2}>租户（访问本租户资源）</option>
                  <option value={3}>全部（跨租户/跨所有者）</option>
                </select>
                <p className="text-xs text-muted mt-1">数据范围 ≥ 3 的角色可越过所有权校验访问任意文件（替代原 isAdmin 旁路）。</p>
              </div>
              <div>
                <label className="block text-xs font-medium text-muted mb-1">权限分配</label>
                <div className="space-y-3 max-h-60 overflow-auto border border-border rounded-md p-3 bg-surface-2/50">
                  {Object.keys(permGroups).length === 0 && (
                    <div className="text-xs text-muted text-center py-4">暂无权限定义</div>
                  )}
                  {Object.entries(permGroups).map(([mod, perms]) => {
                    const expanded = expandedModules.has(mod);
                    const allSelected = perms.length > 0 && perms.every((p) => selectedPermIds.has(p.id));
                    return (
                    <div key={mod} className="rounded-md bg-surface border border-border">
                      <div
                        className="flex items-center gap-2 px-2 py-1.5 cursor-pointer hover:bg-surface-2 select-none"
                        onClick={() => toggleModuleExpand(mod)}
                      >
                        <span className="text-muted flex-shrink-0">
                          {expanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                        </span>
                        <input
                          type="checkbox"
                          checked={allSelected}
                          onChange={() => toggleModule(perms)}
                          onClick={(e) => e.stopPropagation()}
                          className="w-4 h-4"
                        />
                        <span className="text-xs font-semibold text-muted">{MODULE_LABELS[mod] || mod}</span>
                        <span className="text-xs text-muted">({perms.length})</span>
                        {allSelected && <span className="text-xs text-primary-600 ml-auto">全选</span>}
                      </div>
                      {expanded && (
                        <div className="ml-6 grid grid-cols-2 gap-1.5 pb-2 pr-2">
                          {perms.map((p) => (
                            <label key={p.id} className="flex items-center gap-1.5 text-xs text-muted cursor-pointer hover:text-fg">
                              <input type="checkbox" checked={selectedPermIds.has(p.id)} onChange={() => togglePerm(p.id)} className="w-3.5 h-3.5" />
                              <span title={p.permissionCode}>{p.permissionName}</span>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                    );
                  })}
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-muted mb-1">状态</label>
                <select
                  value={form.status}
                  onChange={(e) => setForm((f) => ({ ...f, status: Number(e.target.value) }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                >
                  <option value={1}>启用</option>
                  <option value={0}>禁用</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-muted mb-1">描述</label>
                <input
                  type="text"
                  value={form.description}
                  onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                  placeholder="可选"
                />
              </div>
            </div>

            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setModalOpen(false)} className="px-4 py-2 text-sm font-medium text-muted bg-surface-2 hover:bg-surface-2 rounded-md cursor-pointer transition-colors">
                取消
              </button>
              <button onClick={handleSubmit} className="px-4 py-2 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md cursor-pointer transition-colors">
                {editingId ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}