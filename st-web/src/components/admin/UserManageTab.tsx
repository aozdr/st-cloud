import { useState, useEffect, useCallback } from 'react';
import { Ban, Key, Edit3, Shield, UserPlus } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { usePrompt } from '../ui/PromptDialog';
import { formatSize } from '../../lib/utils';
import { useStorageStore } from '../../store/storage';
import type { AdminUser, PageResult, RoleVO } from '../../types';
import { RoleAssignDialog, QuotaEditDialog, CreateUserDialog } from './AdminDialogs';

export default function UserManageTab() {
  const { showToast } = useToast();
  const { prompt } = usePrompt();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [userTotal, setUserTotal] = useState(0);
  const [userPage, setUserPage] = useState(1);
  const [quotaTarget, setQuotaTarget] = useState<AdminUser | null>(null);
  const [roleTarget, setRoleTarget] = useState<AdminUser | null>(null);
  const [allRoles, setAllRoles] = useState<RoleVO[]>([]);
  const [createUserOpen, setCreateUserOpen] = useState(false);

  const fetchUsers = useCallback(async () => {
    try {
      const data: PageResult<AdminUser> = await api.get('/admin/user/list', { params: { page: userPage, size: 20 } });
      setUsers(data.records || []);
      setUserTotal(parseInt(data.total) || 0);
    } catch { /* ignore */ }
  }, [userPage]);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  const handleToggleStatus = async (userId: string, currentStatus: number) => {
    try {
      await api.put(`/admin/user/${userId}`, { status: currentStatus === 1 ? 0 : 1 });
      showToast(currentStatus === 1 ? '用户已禁用' : '用户已启用', 'success');
      fetchUsers();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleResetPassword = async (userId: string) => {
    const pwd = await prompt({ title: '重置密码', message: '请输入新密码', placeholder: '输入新密码' });
    if (!pwd) return;
    try {
      await api.put(`/admin/user/${userId}`, { resetPassword: pwd });
      showToast('密码已重置', 'success');
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleOpenRoleDialog = async (user: AdminUser) => {
    try {
      const list = await api.get<RoleVO[]>('/admin/role/list');
      setAllRoles(list);
      setRoleTarget(user);
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '获取角色列表失败', 'error');
    }
  };

  const openCreateUser = async () => {
    try {
      const list = await api.get<RoleVO[]>('/admin/role/list');
      setAllRoles(list || []);
    } catch { /* ignore */ }
    setCreateUserOpen(true);
  };

  const handleCreateUser = async (form: { username: string; password: string; nickname: string; email: string; phone: string; roleIds: number[] }) => {
    if (!form.username.trim() || !form.password.trim()) {
      showToast('请输入用户名和密码', 'error');
      return;
    }
    try {
      await api.post('/admin/user', {
        username: form.username,
        password: form.password,
        nickname: form.nickname || undefined,
        email: form.email || undefined,
        phone: form.phone || undefined,
        roleIds: form.roleIds,
      });
      showToast('用户已创建', 'success');
      setCreateUserOpen(false);
      fetchUsers();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '创建失败', 'error');
    }
  };

  const handleAssignRoles = async (userId: string, roleIds: string[]) => {
    try {
      await api.put(`/admin/role/user/${userId}`, { roleIds: roleIds.map(Number) });
      showToast('角色已更新', 'success');
      setRoleTarget(null);
      fetchUsers();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

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

  return (
    <>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-base font-semibold text-fg">用户管理</h2>
        <button
          onClick={() => openCreateUser()}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-md transition-colors cursor-pointer"
        >
          <UserPlus className="w-4 h-4" /> 新建用户
        </button>
      </div>
      <div className="bg-surface rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border bg-surface-2">
              <th className="text-left px-4 py-3 font-medium text-muted">用户名</th>
              <th className="text-left px-4 py-3 font-medium text-muted">昵称</th>
              <th className="text-left px-4 py-3 font-medium text-muted">存储用量</th>
              <th className="text-center px-4 py-3 font-medium text-muted">角色</th>
              <th className="text-center px-4 py-3 font-medium text-muted">状态</th>
              <th className="text-center px-4 py-3 font-medium text-muted">操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id} className="border-b border-border hover:bg-surface-2 transition-colors">
                <td className="px-4 py-3 text-fg font-medium">{user.username}</td>
                <td className="px-4 py-3 text-muted">{user.nickname || '-'}</td>
                <td className="px-4 py-3 text-muted text-xs tabular-nums">
                  {formatSize(Number(user.storageUsed))} / {formatSize(Number(user.storageQuota))}
                </td>
                <td className="px-4 py-3 text-center">
                  {user.roles?.some((r) => r.roleCode === 'admin') ? (
                    <span className="inline-flex items-center gap-1 text-xs text-primary-600 bg-primary-500/10 px-2 py-0.5 rounded-md">
                      <Shield className="w-3 h-3" aria-hidden /> 管理员
                    </span>
                  ) : (
                    <span className="text-xs text-muted">普通用户</span>
                  )}
                </td>
                <td className="px-4 py-3 text-center">
                  {user.status === 1 ? (
                    <span className="text-xs text-green-600 dark:text-green-400 bg-green-500/15 px-2 py-0.5 rounded-md">正常</span>
                  ) : (
                    <span className="text-xs text-red-600 dark:text-red-400 bg-red-500/15 px-2 py-0.5 rounded-md">禁用</span>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center justify-center gap-2">
                    <button
                      onClick={() => handleToggleStatus(user.id, user.status)}
                      className="text-muted hover:text-amber-600 dark:text-amber-400 transition-colors cursor-pointer"
                      title={user.status === 1 ? '禁用' : '启用'} aria-label={user.status === 1 ? '禁用' : '启用'}
                    >
                      <Ban className="w-4 h-4" aria-hidden />
                    </button>
                    <button
                      onClick={() => handleResetPassword(user.id)}
                      className="text-muted hover:text-primary-600 transition-colors cursor-pointer"
                      title="重置密码" aria-label="重置密码"
                    >
                      <Key className="w-4 h-4" aria-hidden />
                    </button>
                    <button
                      onClick={() => setQuotaTarget(user)}
                      className="text-muted hover:text-primary-600 transition-colors cursor-pointer"
                      title="修改配额" aria-label="修改配额"
                    >
                      <Edit3 className="w-4 h-4" aria-hidden />
                    </button>
                    <button
                      onClick={() => handleOpenRoleDialog(user)}
                      className="text-muted hover:text-primary-600 transition-colors cursor-pointer"
                      title="分配角色" aria-label="分配角色"
                    >
                      <Shield className="w-4 h-4" aria-hidden />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {userTotal > 20 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-border">
            <span className="text-xs text-muted">共 {userTotal} 条</span>
            <div className="flex gap-2">
              <button
                onClick={() => setUserPage((p) => Math.max(1, p - 1))}
                disabled={userPage <= 1}
                className="px-3 py-1 text-xs text-muted bg-surface border border-border rounded hover:bg-surface-2 disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
              >
                上一页
              </button>
              <span className="px-3 py-1 text-xs text-muted tabular-nums">第 {userPage} 页</span>
              <button
                onClick={() => setUserPage((p) => p + 1)}
                disabled={userPage * 20 >= userTotal}
                className="px-3 py-1 text-xs text-muted bg-surface border border-border rounded hover:bg-surface-2 disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
              >
                下一页
              </button>
            </div>
          </div>
        )}
      </div>

      {quotaTarget && (
        <QuotaEditDialog
          user={quotaTarget}
          onClose={() => setQuotaTarget(null)}
          onSave={(bytes) => handleUpdateQuota(quotaTarget.id, bytes)}
        />
      )}
      {createUserOpen && (
        <CreateUserDialog
          roles={allRoles}
          onClose={() => setCreateUserOpen(false)}
          onSave={handleCreateUser}
        />
      )}
      {roleTarget && (
        <RoleAssignDialog
          user={roleTarget}
          roles={allRoles}
          initialRoleIds={roleTarget.roles?.map((r) => r.id) ?? []}
          onClose={() => setRoleTarget(null)}
          onSave={(ids) => handleAssignRoles(roleTarget.id, ids)}
        />
      )}
    </>
  );
}