import { useState, lazy, Suspense } from 'react';
import { Settings } from 'lucide-react';
import { usePermission } from '../lib/permission';
import SpeedLimitPanel from '../components/admin/SpeedLimitPanel';
import RoleManagePanel from '../components/admin/RoleManagePanel';
import DashboardTab from '../components/admin/DashboardTab';
import UserManageTab from '../components/admin/UserManageTab';
import StorageManageTab from '../components/admin/StorageManageTab';

const AuditLogPanel = lazy(() => import('../components/admin/AuditLogPanel'));

type Tab = 'dashboard' | 'users' | 'storage' | 'audit' | 'speedLimit' | 'roles';

export default function AdminPage() {
  const { has, hasAny } = usePermission();
  const [tab, setTab] = useState<Tab>('dashboard');

  const tabs: { v: Tab; label: string; can: boolean }[] = [
    { v: 'dashboard', label: '仪表盘', can: hasAny(['admin:user:manage', 'admin:role:manage', 'admin:audit:view', 'admin:stats:view', 'transfer:speed:limit', 'admin:storage:manage']) },
    { v: 'users', label: '用户管理', can: has('admin:user:manage') },
    { v: 'storage', label: '存储管理', can: has('admin:storage:manage') },
    { v: 'audit', label: '审计日志', can: has('admin:audit:view') },
    { v: 'speedLimit', label: '限速管理', can: has('transfer:speed:limit') },
    { v: 'roles', label: '角色管理', can: has('admin:role:manage') },
  ];

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-6 py-4 border-b border-stone-200 bg-white">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
            <Settings className="w-4 h-4 text-white" />
          </div>
          <h1 className="text-lg font-semibold text-stone-900">系统管理</h1>
        </div>
        <div className="flex gap-1 bg-stone-100 rounded-lg p-1">
          {tabs.filter((t) => t.can).map((t) => (
            <button
              key={t.v}
              onClick={() => setTab(t.v)}
              className={`px-4 py-1.5 text-sm font-medium rounded-md transition cursor-pointer ${
                tab === t.v ? 'bg-white text-stone-900 shadow-sm' : 'text-stone-500 hover:text-stone-700'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {tab === 'dashboard' && <DashboardTab />}
        {tab === 'users' && <UserManageTab />}
        {tab === 'storage' && <StorageManageTab />}
        {tab === 'audit' && (
          <Suspense fallback={<div className="py-10 text-center text-sm text-stone-400">加载中...</div>}>
            <AuditLogPanel />
          </Suspense>
        )}
        {tab === 'speedLimit' && <SpeedLimitPanel />}
        {tab === 'roles' && <RoleManagePanel />}
      </div>
    </div>
  );
}