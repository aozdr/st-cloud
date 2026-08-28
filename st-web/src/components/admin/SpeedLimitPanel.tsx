import { useState, useEffect, useCallback } from 'react';
import { Plus, Edit, Trash2, X, ArrowUp, ArrowDown } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { useConfirm } from '../ui/ConfirmDialog';
import type { SpeedLimitRule, AdminUser, RoleVO, PageResult } from '../../types';

type Scope = 0 | 1;
type Unit = 'kb' | 'mb';

interface FormState {
  ruleName: string;
  scope: Scope;
  targetId: string;
  targetCode: string;
  targetName: string;
  uploadUnlimited: boolean;
  uploadValue: number;
  uploadUnit: Unit;
  downloadUnlimited: boolean;
  downloadValue: number;
  downloadUnit: Unit;
  enabled: number;
  description: string;
}

const emptyForm: FormState = {
  ruleName: '',
  scope: 0,
  targetId: '',
  targetCode: '',
  targetName: '',
  uploadUnlimited: true,
  uploadValue: 1,
  uploadUnit: 'mb',
  downloadUnlimited: true,
  downloadValue: 1,
  downloadUnit: 'mb',
  enabled: 1,
  description: '',
};

function kbToDisplay(kbps: number): { value: number; unit: Unit } {
  if (kbps === 0) return { value: 1, unit: 'mb' };
  if (kbps >= 1024 && kbps % 1024 === 0) return { value: kbps / 1024, unit: 'mb' };
  return { value: kbps, unit: 'kb' };
}

function displayToKb(unlimited: boolean, value: number, unit: Unit): number {
  if (unlimited) return 0;
  const kb = unit === 'mb' ? value * 1024 : value;
  return kb > 0 ? kb : 0;
}

export default function SpeedLimitPanel() {
  const { showToast } = useToast();
  const { confirm } = useConfirm();
  const [rules, setRules] = useState<SpeedLimitRule[]>([]);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);

  const fetchRules = useCallback(async () => {
    try {
      const data: SpeedLimitRule[] = await api.get('/admin/speed-limit/list');
      setRules(data || []);
    } catch {
      /* ignore */
    }
  }, []);

  const fetchTargets = useCallback(async () => {
    try {
      const ud: PageResult<AdminUser> = await api.get('/admin/user/list', { params: { page: 1, size: 1000 } });
      setUsers(ud.records || []);
    } catch {
      /* ignore */
    }
    try {
      const rd: RoleVO[] = await api.get('/admin/role/list');
      setRoles(rd || []);
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    fetchRules();
    fetchTargets();
  }, [fetchRules, fetchTargets]);

  const openCreate = () => {
    setEditingId(null);
    setForm(emptyForm);
    setModalOpen(true);
  };

  const openEdit = (r: SpeedLimitRule) => {
    const up = kbToDisplay(r.uploadSpeedLimit);
    const down = kbToDisplay(r.downloadSpeedLimit);
    setEditingId(r.id);
    setForm({
      ruleName: r.ruleName,
      scope: r.scope as Scope,
      targetId: r.targetId,
      targetCode: r.targetCode || '',
      targetName: r.targetName || '',
      uploadUnlimited: r.uploadSpeedLimit === 0,
      uploadValue: up.value,
      uploadUnit: up.unit,
      downloadUnlimited: r.downloadSpeedLimit === 0,
      downloadValue: down.value,
      downloadUnit: down.unit,
      enabled: r.enabled,
      description: r.description || '',
    });
    setModalOpen(true);
  };

  const onScopeChange = (scope: Scope) => {
    setForm((f) => ({ ...f, scope, targetId: '', targetCode: '', targetName: '' }));
  };

  const onTargetChange = (id: string) => {
    if (form.scope === 0) {
      const u = users.find((x) => String(x.id) === String(id));
      setForm((f) => ({ ...f, targetId: id, targetCode: u?.username || '', targetName: u?.nickname || u?.username || '' }));
    } else {
      const r = roles.find((x) => String(x.id) === String(id));
      setForm((f) => ({ ...f, targetId: id, targetCode: r?.roleCode || '', targetName: r?.roleName || '' }));
    }
  };

  const handleSubmit = async () => {
    if (!form.ruleName.trim()) {
      showToast('请输入规则名称', 'error');
      return;
    }
    if (!form.targetId) {
      showToast('请选择限制对象', 'error');
      return;
    }
    const payload = {
      ruleName: form.ruleName,
      scope: form.scope,
      targetId: form.targetId,
      targetCode: form.targetCode,
      targetName: form.targetName,
      uploadSpeedLimit: displayToKb(form.uploadUnlimited, form.uploadValue, form.uploadUnit),
      downloadSpeedLimit: displayToKb(form.downloadUnlimited, form.downloadValue, form.downloadUnit),
      enabled: form.enabled,
      description: form.description,
    };
    if (payload.uploadSpeedLimit === 0 && payload.downloadSpeedLimit === 0) {
      showToast('上传和下载不能同时不限速', 'error');
      return;
    }
    try {
      if (editingId) {
        await api.put(`/admin/speed-limit/${editingId}`, payload);
        showToast('规则已更新', 'success');
      } else {
        await api.post('/admin/speed-limit', payload);
        showToast('规则已创建', 'success');
      }
      setModalOpen(false);
      fetchRules();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleToggle = async (r: SpeedLimitRule) => {
    try {
      await api.put(`/admin/speed-limit/${r.id}/toggle`);
      fetchRules();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const handleDelete = async (r: SpeedLimitRule) => {
    const ok = await confirm({ title: '删除规则', message: `确认删除规则「${r.ruleName}」？`, danger: true, confirmText: '删除' });
    if (!ok) return;
    try {
      await api.delete(`/admin/speed-limit/${r.id}`);
      showToast('已删除', 'success');
      fetchRules();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '操作失败', 'error');
    }
  };

  const fmtSpeed = (kb: number) => (kb === 0 ? '不限速' : kb >= 1024 && kb % 1024 === 0 ? `${kb / 1024} MB/s` : `${kb} KB/s`);

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <p className="text-sm text-muted">
          为单个用户或角色配置上传/下载速度上限，用户规则与所属角色规则合并取最严格值，0 表示不限速。
        </p>
        <button
          onClick={openCreate}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          新建规则
        </button>
      </div>

      <div className="bg-surface rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border bg-surface-2">
              <th className="text-left px-4 py-3 font-medium text-muted">规则名称</th>
              <th className="text-left px-4 py-3 font-medium text-muted">限制对象</th>
              <th className="text-left px-4 py-3 font-medium text-muted">上传限速</th>
              <th className="text-left px-4 py-3 font-medium text-muted">下载限速</th>
              <th className="text-center px-4 py-3 font-medium text-muted">状态</th>
              <th className="text-center px-4 py-3 font-medium text-muted">操作</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id} className="border-b border-border hover:bg-surface-2 transition-colors">
                <td className="px-4 py-3 text-muted">{r.ruleName}</td>
                <td className="px-4 py-3 text-muted">
                  <span className="text-xs font-medium text-primary-600 bg-primary-500/10 px-2 py-0.5 rounded mr-2">
                    {r.scope === 0 ? '用户' : '角色'}
                  </span>
                  {r.targetName || r.targetCode || r.targetId}
                </td>
                <td className="px-4 py-3 text-muted">{fmtSpeed(r.uploadSpeedLimit)}</td>
                <td className="px-4 py-3 text-muted">{fmtSpeed(r.downloadSpeedLimit)}</td>
                <td className="px-4 py-3 text-center">
                  <button
                    onClick={() => handleToggle(r)}
                    className={`text-xs px-2 py-0.5 rounded cursor-pointer transition-colors ${
                      r.enabled === 1 ? 'text-green-600 dark:text-green-400 bg-green-500/15 hover:bg-green-100' : 'text-muted bg-surface-2 hover:bg-surface-2'
                    }`}
                  >
                    {r.enabled === 1 ? '启用' : '禁用'}
                  </button>
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center justify-center gap-3">
                    <button onClick={() => openEdit(r)} className="text-muted hover:text-primary-600 transition-colors cursor-pointer" title="编辑">
                      <Edit className="w-4 h-4" />
                    </button>
                    <button onClick={() => handleDelete(r)} className="text-muted hover:text-red-500 transition-colors cursor-pointer" title="删除">
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rules.length === 0 && <div className="py-10 text-center text-sm text-muted">暂无限速规则，点击「新建规则」创建</div>}
      </div>

      {modalOpen && (
        <div className="fixed inset-0 bg-black/40 backdrop-blur-sm flex items-center justify-center z-50 overscroll-contain" role="presentation" onClick={() => setModalOpen(false)}>
          <div className="bg-surface rounded-lg w-full max-w-lg p-6 max-h-[90vh] overflow-auto" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-fg">{editingId ? '编辑限速规则' : '新建限速规则'}</h2>
              <button onClick={() => setModalOpen(false)} aria-label="关闭" className="text-muted hover:text-fg transition-colors cursor-pointer rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <X className="w-5 h-5" aria-hidden />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-muted mb-1">规则名称</label>
                <input
                  type="text"
                  value={form.ruleName}
                  onChange={(e) => setForm((f) => ({ ...f, ruleName: e.target.value }))}
                  className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                  placeholder="如：普通用户下载限速"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-muted mb-1">限制对象</label>
                  <select
                    value={form.scope}
                    onChange={(e) => onScopeChange(Number(e.target.value) as Scope)}
                    className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                  >
                    <option value={0}>按用户</option>
                    <option value={1}>按角色</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-muted mb-1">{form.scope === 0 ? '选择用户' : '选择角色'}</label>
                  <select
                    value={form.targetId}
                    onChange={(e) => onTargetChange(e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-border rounded-md focus:outline-none focus:border-primary-500"
                  >
                    <option value="">请选择</option>
                    {form.scope === 0
                      ? users.map((u) => (
                          <option key={u.id} value={u.id}>
                            {u.nickname || u.username}（{u.username}）
                          </option>
                        ))
                      : roles.map((r) => (
                          <option key={r.id} value={r.id}>
                            {r.roleName}（{r.roleCode}）
                          </option>
                        ))}
                  </select>
                </div>
              </div>

              <SpeedInput
                icon={<ArrowUp className="w-4 h-4 text-primary-500" />}
                title="上传限速"
                unlimited={form.uploadUnlimited}
                value={form.uploadValue}
                unit={form.uploadUnit}
                onToggle={(u) => setForm((f) => ({ ...f, uploadUnlimited: u }))}
                onValue={(v) => setForm((f) => ({ ...f, uploadValue: v }))}
                onUnit={(u) => setForm((f) => ({ ...f, uploadUnit: u }))}
              />
              <div className="border-t border-border" />
              <SpeedInput
                icon={<ArrowDown className="w-4 h-4 text-emerald-500" />}
                title="下载限速"
                unlimited={form.downloadUnlimited}
                value={form.downloadValue}
                unit={form.downloadUnit}
                onToggle={(u) => setForm((f) => ({ ...f, downloadUnlimited: u }))}
                onValue={(v) => setForm((f) => ({ ...f, downloadValue: v }))}
                onUnit={(u) => setForm((f) => ({ ...f, downloadUnit: u }))}
              />

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

              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={form.enabled === 1}
                  onChange={(e) => setForm((f) => ({ ...f, enabled: e.target.checked ? 1 : 0 }))}
                  className="w-4 h-4 accent-primary-600"
                />
                <span className="text-sm text-muted">启用该规则</span>
              </label>
            </div>

            <div className="flex justify-end gap-2 mt-6">
              <button onClick={() => setModalOpen(false)} className="px-4 py-2 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer">
                取消
              </button>
              <button onClick={handleSubmit} className="px-4 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer">
                {editingId ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

interface SpeedInputProps {
  icon: React.ReactNode;
  title: string;
  unlimited: boolean;
  value: number;
  unit: Unit;
  onToggle: (u: boolean) => void;
  onValue: (v: number) => void;
  onUnit: (u: Unit) => void;
}

function SpeedInput({ icon, title, unlimited, value, unit, onToggle, onValue, onUnit }: SpeedInputProps) {
  return (
    <div className="py-3">
      <div className="flex items-center gap-2 mb-2">
        {icon}
        <label className="text-sm font-medium text-muted">{title}</label>
      </div>
      <label className="flex items-center gap-2 mb-2 cursor-pointer">
        <button
          onClick={() => onToggle(!unlimited)}
          className={`relative w-9 h-5 rounded-full transition-colors duration-200 cursor-pointer ${unlimited ? 'bg-muted/50' : 'bg-primary-600'}`}
        >
          <span className={`absolute top-0.5 w-4 h-4 bg-surface rounded-full shadow-sm transition-transform duration-200 ${unlimited ? 'left-0.5' : 'left-[18px]'}`} />
        </button>
        <span className={`text-sm ${unlimited ? 'text-muted' : 'text-muted'}`}>{unlimited ? '不限速' : '自定义限速'}</span>
      </label>
      {!unlimited && (
        <div className="flex items-center gap-2">
          <input
            type="number"
            min={1}
            value={value || ''}
            onChange={(e) => {
              const v = parseInt(e.target.value, 10);
              onValue(isNaN(v) || v < 1 ? 1 : v);
            }}
            className="flex-1 px-3 py-2 border border-border rounded-lg text-sm text-fg focus:outline-none focus:border-primary-400"
            placeholder="输入数值"
          />
          <div className="flex rounded-lg overflow-hidden border border-border">
            <button onClick={() => onUnit('kb')} className={`px-3 py-2 text-sm font-medium transition-colors cursor-pointer ${unit === 'kb' ? 'bg-primary-600 text-white' : 'bg-surface text-muted hover:bg-surface-2'}`}>
              KB/s
            </button>
            <button onClick={() => onUnit('mb')} className={`px-3 py-2 text-sm font-medium transition-colors cursor-pointer ${unit === 'mb' ? 'bg-primary-600 text-white' : 'bg-surface text-muted hover:bg-surface-2'}`}>
              MB/s
            </button>
          </div>
        </div>
      )}
    </div>
  );
}