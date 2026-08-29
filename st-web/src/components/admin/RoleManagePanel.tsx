import { useState, useEffect, useCallback, useMemo, memo } from 'react';
import { Plus, Edit3, Trash2, X, Shield, Search, KeyRound, CheckCircle2, Ban } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { useConfirm } from '../ui/ConfirmDialog';
import { PermissionTree } from '../ui/PermissionTree';
import { cn } from '../../lib/utils';
import type { RoleVO, PermissionVO } from '../../types';

interface RoleFormState {
  roleCode: string;
  roleName: string;
  description: string;
  status: number;
}

const emptyForm: RoleFormState = {
  roleCode: '',
  roleName: '',
  description: '',
  status: 1,
};

/** 单个角色行（memo 优化，避免父级搜索变化引发整表重渲染） */
const RoleRow = memo(function RoleRow({
  role,
  onEdit,
  onDelete,
}: {
  role: RoleVO;
  onEdit: (role: RoleVO) => void;
  onDelete: (role: RoleVO) => void;
}) {
  const permCount = role.permissions?.length ?? 0;
  return (
    <div className="flex items-center gap-4 px-5 py-4 border-b border-border last:border-b-0 hover:bg-surface-2/50 transition-colors">
      <div className="w-10 h-10 rounded-lg bg-primary-500/10 flex items-center justify-center flex-shrink-0">
        <Shield className="w-5 h-5 text-primary-600" aria-hidden />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-fg truncate">{role.roleName}</span>
          {role.builtIn && (
            <span className="text-xs text-primary-600 bg-primary-500/10 px-2 py-0.5 rounded">内置</span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 mt-1 text-xs text-muted">
          <span className="font-mono">{role.roleCode}</span>
          <span className="inline-flex items-center gap-1">
            <KeyRound className="w-3 h-3" aria-hidden />
            {permCount} 项权限
          </span>
          <span
            className={cn(
              'inline-flex items-center gap-1 px-1.5 py-0.5 rounded',
              role.status === 1 ? 'text-green-600 dark:text-green-400 bg-green-500/15' : 'text-red-600 dark:text-red-400 bg-red-500/15',
            )}
          >
            {role.status === 1 ? <CheckCircle2 className="w-3 h-3" aria-hidden /> : <Ban className="w-3 h-3" aria-hidden />}
            {role.status === 1 ? '启用' : '禁用'}
          </span>
          {role.description && <span className="text-muted truncate max-w-[220px]">{role.description}</span>}
        </div>
      </div>
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={() => onEdit(role)}
          className="p-2 text-muted hover:text-primary-600 hover:bg-primary-500/10 rounded-md transition-colors cursor-pointer"
          title="编辑" aria-label={`编辑 ${role.roleName}`}
        >
          <Edit3 className="w-4 h-4" aria-hidden />
        </button>
        <button
          onClick={() => onDelete(role)}
          disabled={role.builtIn}
          className="p-2 text-muted hover:text-red-500 hover:bg-red-500/10 rounded-md transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
          title={role.builtIn ? '内置角色不可删除' : '删除'} aria-label={`删除 ${role.roleName}`}
        >
          <Trash2 className="w-4 h-4" aria-hidden />
        </button>
      </div>
    </div>
  );
});

export default function RoleManagePanel() {
  const { showToast } = useToast();
  const { confirm } = useConfirm();
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [permGroups, setPermGroups] = useState<Record<string, PermissionVO[]>>({});
  const [selectedPermIds, setSelectedPermIds] = useState<Set<string>>(new Set());
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<RoleFormState>(emptyForm);
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(false);

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

  const filteredRoles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return roles;
    return roles.filter(
      (r) => r.roleName.toLowerCase().includes(q) || r.roleCode.toLowerCase().includes(q),
    );
  }, [roles, searchQuery]);

  const openCreate = useCallback(() => {
    setEditingId(null);
    setForm(emptyForm);
    setSelectedPermIds(new Set());
    setModalOpen(true);
  }, []);

  const openEdit = useCallback((r: RoleVO) => {
    setEditingId(r.id);
    setForm({
      roleCode: r.roleCode,
      roleName: r.roleName,
      description: r.description || '',
      status: r.status,
    });
    setSelectedPermIds(new Set(r.permissions?.map((p) => p.id) ?? []));
    setModalOpen(true);
  }, []);

  const handleSubmit = useCallback(async () => {
    if (!form.roleCode.trim()) {
      showToast('请输入角色编码', 'error');
      return;
    }
    if (!form.roleName.trim()) {
      showToast('请输入角色名称', 'error');
      return;
    }
    setLoading(true);
    const payload = {
      roleCode: form.roleCode.trim(),
      roleName: form.roleName.trim(),
      description: form.description.trim() || undefined,
      status: form.status,
    };
    const permIds = [...selectedPermIds];
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
    } finally {
      setLoading(false);
    }
  }, [form, selectedPermIds, editingId, fetchRoles, showToast]);

  const handleDelete = useCallback(
    async (r: RoleVO) => {
      if (r.builtIn) {
        showToast('内置角色不可删除', 'warning');
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
    },
    [confirm, fetchRoles, showToast],
  );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-fg">角色管理</h2>
          <p className="text-xs text-muted mt-0.5">管理角色与操作权限。当前为单租户部署，数据范围固定为「本人」，不做跨用户/跨租户数据访问。</p>
        </div>
        <button
          onClick={openCreate}
          className="inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-lg transition-colors cursor-pointer"
        >
          <Plus className="w-4 h-4" /> 新建角色
        </button>
      </div>

      <div className="relative max-w-xs">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted pointer-events-none" />
        <input
          type="search"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="搜索角色名称或编码"
          aria-label="搜索角色"
          className="input-field pl-9"
        />
      </div>

      <div className="bg-surface rounded-xl border border-border overflow-hidden">
        {filteredRoles.length === 0 ? (
          <div className="py-14 text-center">
            <Shield className="w-10 h-10 text-muted/40 mx-auto mb-2" aria-hidden />
            <p className="text-sm text-muted">{searchQuery ? '未找到匹配角色' : '暂无角色，点击「新建角色」创建'}</p>
          </div>
        ) : (
          <div>
            {filteredRoles.map((r) => (
              <RoleRow key={r.id} role={r} onEdit={openEdit} onDelete={handleDelete} />
            ))}
          </div>
        )}
      </div>

      {modalOpen && (
        <div className="fixed inset-0 bg-black/40 backdrop-blur-sm flex items-center justify-center z-50 overscroll-contain" role="presentation" onClick={() => setModalOpen(false)}>
          <div className="bg-surface rounded-xl w-full max-w-3xl flex flex-col max-h-[92vh] overflow-hidden shadow-lg" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={editingId ? '编辑角色' : '新建角色'}>
            {/* 头部固定 */}
            <div className="flex-shrink-0 flex items-center justify-between px-6 py-4 border-b border-border">
              <h3 className="text-base font-semibold text-fg">{editingId ? '编辑角色' : '新建角色'}</h3>
              <button onClick={() => setModalOpen(false)} aria-label="关闭" className="text-muted hover:text-fg transition-colors cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <X className="w-5 h-5" aria-hidden />
              </button>
            </div>

            {/* body：表单 + 权限树整体滚动 */}
            <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4 space-y-4">
              {/* 表单区：紧凑，置顶 */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label htmlFor="role-code" className="block text-xs font-medium text-muted mb-1">角色编码</label>
                  <input
                    id="role-code"
                    type="text"
                    value={form.roleCode}
                    disabled={!!editingId}
                    onChange={(e) => setForm((f) => ({ ...f, roleCode: e.target.value }))}
                    className="input-field disabled:bg-surface-2 disabled:text-muted"
                    placeholder="如：auditor"
                  />
                  {editingId && <p className="text-xs text-muted mt-1">编码创建后不可修改</p>}
                </div>
                <div>
                  <label htmlFor="role-name" className="block text-xs font-medium text-muted mb-1">角色名称</label>
                  <input
                    id="role-name"
                    type="text"
                    value={form.roleName}
                    onChange={(e) => setForm((f) => ({ ...f, roleName: e.target.value }))}
                    className="input-field"
                    placeholder="如：审计员"
                  />
                </div>
              </div>

              <div className="flex items-end gap-3">
                <div className="flex-1">
                  <label htmlFor="role-desc" className="block text-xs font-medium text-muted mb-1">描述</label>
                  <input
                    id="role-desc"
                    type="text"
                    value={form.description}
                    onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                    className="input-field"
                    placeholder="可选"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-muted mb-1">状态</label>
                  <div className="inline-flex rounded-lg border border-border bg-surface-2 p-1">
                    {[{ v: 1, label: '启用' }, { v: 0, label: '禁用' }].map((o) => (
                      <button
                        key={o.v}
                        type="button"
                        onClick={() => setForm((f) => ({ ...f, status: o.v }))}
                        className={cn(
                          'px-3 py-1.5 text-sm rounded-md transition-colors cursor-pointer',
                          form.status === o.v ? 'bg-surface text-fg shadow-sm' : 'text-muted hover:text-fg',
                        )}
                      >
                        {o.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>

              {/* 权限区：表单下方主体 */}
              <div>
                <label className="block text-sm font-medium text-muted mb-2">权限分配</label>
                <PermissionTree
                  groups={permGroups}
                  selectedIds={selectedPermIds}
                  onChange={setSelectedPermIds}
                />
              </div>
            </div>

            {/* 底部固定，实背景遮盖滚动内容 */}
            <div className="flex-shrink-0 flex justify-end gap-3 px-6 py-4 border-t border-border bg-surface shadow-[0_-4px_12px_-4px_rgb(0_0_0_/_0.10)]">
              <button onClick={() => setModalOpen(false)} className="btn-secondary">取消</button>
              <button onClick={handleSubmit} disabled={loading} className="btn-primary">
                {loading ? '保存中…' : editingId ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
