import { useState, useEffect } from 'react';
import { Dialog } from '../ui/Dialog';
import { RoleMultiSelect } from '../ui/RoleMultiSelect';
import { formatSize } from '../../lib/utils';
import type { AdminUser, RoleVO } from '../../types';

export function RoleAssignDialog({ user, roles, initialRoleIds, onClose, onSave }: {
  user: AdminUser;
  roles: RoleVO[];
  initialRoleIds: string[];
  onClose: () => void;
  onSave: (roleIds: string[]) => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set(initialRoleIds));

  return (
    <Dialog
      onClose={onClose}
      width="max-w-2xl"
      title="分配角色"
      description={user.username}
      footer={
        <>
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave([...selected])} className="btn-primary">保存</button>
        </>
      }
    >
        <RoleMultiSelect
          roles={roles}
          selectedIds={selected}
          onChange={setSelected}
          placeholder="选择角色"
        />
    </Dialog>
  );
}

export function QuotaEditDialog({ user, onClose, onSave }: {
  user: AdminUser;
  onClose: () => void;
  onSave: (quotaBytes: number) => void;
}) {
  const [value, setValue] = useState('');
  const [unit, setUnit] = useState('GB');

  useEffect(() => {
    const quotaNum = Number(user.storageQuota || 0);
    if (quotaNum === 0) {
      setValue('');
      setUnit('GB');
    } else {
      const gb = quotaNum / (1024 ** 3);
      if (gb >= 1) { setValue(String(Math.round(gb * 100) / 100)); setUnit('GB'); }
      else { setValue(String(Math.round(quotaNum / (1024 ** 2) * 100) / 100)); setUnit('MB'); }
    }
  }, [user]);

  const quotaBytes = value ? Math.round(parseFloat(value) * (unit === 'GB' ? 1024 ** 3 : 1024 ** 2)) : 0;

  return (
    <Dialog
      onClose={onClose}
      width="max-w-lg"
      title="修改存储配额"
      description={`${user.username}（已用 ${formatSize(Number(user.storageUsed))}）`}
      footer={
        <>
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave(quotaBytes)} className="btn-primary">保存</button>
        </>
      }
    >

        <label className="block text-sm font-medium text-stone-700 mb-2">配额大小</label>
        <div className="flex items-center gap-2 mb-3">
          <input
            type="number"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="输入0表示不限制"
            className="input-field flex-1"
            min="0"
            step="0.1"
          />
          <select
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
            className="input-field w-20 cursor-pointer"
          >
            <option value="MB">MB</option>
            <option value="GB">GB</option>
            <option value="TB">TB</option>
          </select>
        </div>

        {value && parseFloat(value) > 0 && (
          <p className="text-xs text-stone-400 mb-2">
            = {formatSize(quotaBytes)}{unit === 'TB' ? ' (' + value + ' ' + unit + ')' : ''}
          </p>
        )}
        {!value && (
          <p className="text-xs text-amber-600 mb-2">不填写或填0表示不限制</p>
        )}

        <div className="flex flex-wrap gap-1.5">
          {[
            { label: '5 GB', val: 5 * 1024 ** 3 },
            { label: '10 GB', val: 10 * 1024 ** 3 },
            { label: '50 GB', val: 50 * 1024 ** 3 },
            { label: '100 GB', val: 100 * 1024 ** 3 },
            { label: '1 TB', val: 1024 ** 4 },
            { label: '不限制', val: 0 },
          ].map((preset) => (
            <button
              key={preset.label}
              onClick={() => {
                if (preset.val === 0) { setValue(''); }
                else {
                  const gb = preset.val / (1024 ** 3);
                  setValue(String(Math.round(gb * 100) / 100));
                  setUnit('GB');
                }
              }}
              className="px-3 py-1.5 text-xs text-stone-600 bg-stone-50 border border-stone-200 hover:bg-primary-50 hover:text-primary-600 hover:border-primary-300 rounded-md cursor-pointer transition-colors"
            >
              {preset.label}
            </button>
          ))}
        </div>

    </Dialog>
  );
}

export function CloudCapacityEditDialog({ currentCapacity, used, onClose, onSave }: {
  currentCapacity: number | null;
  used: number;
  onClose: () => void;
  onSave: (capacityBytes: number) => void;
}) {
  const [value, setValue] = useState("");
  const [unit, setUnit] = useState("GB");

  useEffect(() => {
    const capNum = Number(currentCapacity || 0);
    if (capNum === 0) {
      setValue("");
      setUnit("GB");
    } else {
      const gb = capNum / (1024 ** 3);
      if (gb >= 1) { setValue(String(Math.round(gb * 100) / 100)); setUnit("GB"); }
      else { setValue(String(Math.round(capNum / (1024 ** 2) * 100) / 100)); setUnit("MB"); }
    }
  }, [currentCapacity]);

  const capacityBytes = value ? Math.round(parseFloat(value) * (unit === "TB" ? 1024 ** 4 : unit === "GB" ? 1024 ** 3 : 1024 ** 2)) : 0;

  return (
    <Dialog
      onClose={onClose}
      width="max-w-lg"
      title="云盘总容量"
      description={`当前已用 ${formatSize(used)}`}
      footer={
        <>
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave(capacityBytes)} className="btn-primary">保存</button>
        </>
      }
    >
        <label className="block text-sm font-medium text-stone-700 mb-2">总容量</label>
        <div className="flex items-center gap-2 mb-3">
          <input
            type="number"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="输入0表示不限制"
            className="input-field flex-1"
            min="0"
            step="0.1"
          />
          <select value={unit} onChange={(e) => setUnit(e.target.value)} className="input-field w-20 cursor-pointer">
            <option value="MB">MB</option>
            <option value="GB">GB</option>
            <option value="TB">TB</option>
          </select>
        </div>

        {value && parseFloat(value) > 0 && (
          <p className="text-xs text-stone-400 mb-2">= {formatSize(capacityBytes)}</p>
        )}
        {!value && (
          <p className="text-xs text-amber-600 mb-2">不填写或填0表示不限制</p>
        )}

        <div className="flex flex-wrap gap-1.5">
          {[
            { label: "50 GB", val: 50 * 1024 ** 3 },
            { label: "100 GB", val: 100 * 1024 ** 3 },
            { label: "500 GB", val: 500 * 1024 ** 3 },
            { label: "1 TB", val: 1024 ** 4 },
            { label: "2 TB", val: 2 * 1024 ** 4 },
            { label: "不限制", val: 0 },
          ].map((preset) => (
            <button
              key={preset.label}
              onClick={() => {
                if (preset.val === 0) { setValue(""); }
                else {
                  const gb = preset.val / (1024 ** 3);
                  setValue(String(Math.round(gb * 100) / 100));
                  setUnit("GB");
                }
              }}
              className="px-3 py-1.5 text-xs text-stone-600 bg-stone-50 border border-stone-200 hover:bg-primary-50 hover:text-primary-600 hover:border-primary-300 rounded-md cursor-pointer transition-colors"
            >
              {preset.label}
            </button>
          ))}
        </div>

    </Dialog>
  );
}

export function CreateUserDialog({ roles, onClose, onSave }: {
  roles: RoleVO[];
  onClose: () => void;
  onSave: (form: { username: string; password: string; nickname: string; email: string; phone: string; roleIds: number[] }) => void;
}) {
  const [form, setForm] = useState({ username: '', password: '', nickname: '', email: '', phone: '' });
  const [roleIds, setRoleIds] = useState<Set<string>>(() => {
    const userRole = roles.find((r) => r.roleCode === 'user');
    return new Set(userRole ? [userRole.id] : []);
  });

  return (
    <Dialog
      onClose={onClose}
      width="max-w-xl"
      title="新建用户"
      footer={
        <>
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave({
            ...form,
            username: form.username.trim(),
            nickname: form.nickname.trim(),
            email: form.email.trim(),
            phone: form.phone.trim(),
            roleIds: [...roleIds].map(Number),
          })} className="btn-primary">创建</button>
        </>
      }
    >
      <div className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-stone-700 mb-1.5">用户名</label>
          <input type="text" value={form.username} onChange={(e) => setForm((f) => ({ ...f, username: e.target.value }))} className="input-field" placeholder="登录用户名" />
        </div>
        <div>
          <label className="block text-sm font-medium text-stone-700 mb-1.5">密码</label>
          <input type="password" value={form.password} onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))} className="input-field" placeholder="初始密码" />
        </div>
        <div>
          <label className="block text-sm font-medium text-stone-700 mb-1.5">昵称</label>
          <input type="text" value={form.nickname} onChange={(e) => setForm((f) => ({ ...f, nickname: e.target.value }))} className="input-field" placeholder="可选" />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-stone-700 mb-1.5">邮箱</label>
            <input type="text" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} className="input-field" placeholder="可选" />
          </div>
          <div>
            <label className="block text-sm font-medium text-stone-700 mb-1.5">手机号</label>
            <input type="text" value={form.phone} onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))} className="input-field" placeholder="可选" />
          </div>
        </div>
        <div>
          <label className="block text-sm font-medium text-stone-700 mb-1.5">角色</label>
          <RoleMultiSelect
            roles={roles}
            selectedIds={roleIds}
            onChange={setRoleIds}
            placeholder="选择角色"
          />
        </div>
      </div>
    </Dialog>
  );
}