import { useState, lazy, Suspense } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Shield,
  LayoutDashboard,
  Users,
  HardDrive,
  FileText,
  Gauge,
  ShieldCheck,
  ChevronDown,
  LogOut,
  ArrowLeft,
  type LucideIcon,
} from 'lucide-react';
import { usePermission } from '../lib/permission';
import { useAuthStore } from '../store/auth';
import SpeedLimitPanel from '../components/admin/SpeedLimitPanel';
import RoleManagePanel from '../components/admin/RoleManagePanel';
import DashboardTab from '../components/admin/DashboardTab';
import UserManageTab from '../components/admin/UserManageTab';
import StorageManageTab from '../components/admin/StorageManageTab';
import ShareSecurityPanel from '../components/admin/ShareSecurityPanel';

const AuditLogPanel = lazy(() => import('../components/admin/AuditLogPanel'));

type Tab = 'dashboard' | 'users' | 'storage' | 'audit' | 'speedLimit' | 'roles' | 'security';

export default function AdminPage() {
  const { has, hasAny } = usePermission();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const [tab, setTab] = useState<Tab>('dashboard');
  const [menuOpen, setMenuOpen] = useState(false);

  const display = user?.username || user?.nickname || 'admin';
  const avatarChar = display.charAt(0).toUpperCase();

  const tabs: { v: Tab; label: string; icon: LucideIcon; can: boolean }[] = [
    { v: 'dashboard', label: '仪表盘', icon: LayoutDashboard, can: hasAny(['admin:user:manage', 'admin:role:manage', 'admin:audit:view', 'admin:stats:view', 'transfer:speed:limit', 'admin:storage:manage']) },
    { v: 'users', label: '用户管理', icon: Users, can: has('admin:user:manage') },
    { v: 'storage', label: '存储管理', icon: HardDrive, can: has('admin:storage:manage') },
    { v: 'audit', label: '审计日志', icon: FileText, can: has('admin:audit:view') },
    { v: 'speedLimit', label: '限速管理', icon: Gauge, can: has('transfer:speed:limit') },
    { v: 'roles', label: '角色管理', icon: ShieldCheck, can: has('admin:role:manage') },
    { v: 'security', label: '分享安全', icon: Shield, can: has('admin:share:security') },
  ];

  const canShow = (v: Tab) => tabs.find((t) => t.v === v)?.can;

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="flex h-screen flex-col bg-[#F6F8FA]">
      {/* 顶部栏 */}
      <header className="h-14 flex items-center justify-between px-5 bg-white border-b border-border flex-shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
            <Shield className="w-4 h-4 text-white" aria-hidden />
          </div>
          <span className="text-base font-semibold text-fg">系统管理</span>
        </div>
        <div className="relative">
          <button
            type="button"
            onClick={() => setMenuOpen((o) => !o)}
            className="flex items-center gap-2 px-2 py-1 rounded-md hover:bg-surface transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400"
          >
            <span className="w-7 h-7 rounded-full bg-[#C9CFDA] text-[#3A4A5A] flex items-center justify-center text-xs font-semibold">
              {avatarChar}
            </span>
            <span className="text-sm text-fg">{display}</span>
            <ChevronDown className="w-4 h-4 text-muted" aria-hidden />
          </button>
          {menuOpen && (
            <div className="absolute right-0 top-10 z-50 w-44 bg-white border border-border rounded-lg shadow-md p-1">
              <div className="px-3 py-2 text-sm text-fg border-b border-border">{display}</div>
              <button
                type="button"
                onClick={handleLogout}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-muted hover:bg-surface rounded-md cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400"
              >
                <LogOut className="w-4 h-4" aria-hidden />
                退出登录
              </button>
            </div>
          )}
        </div>
      </header>

      {/* 主体：左侧导航 + 内容区 */}
      <div className="flex flex-1 min-h-0">
        <aside className="w-60 flex flex-col bg-white border-r border-border flex-shrink-0">
          <nav className="flex-1 py-4">
            {tabs
              .filter((t) => t.can)
              .map((t) => {
                const Icon = t.icon;
                const active = tab === t.v;
                return (
                  <button
                    key={t.v}
                    type="button"
                    onClick={() => setTab(t.v)}
                    className={`w-full flex items-center gap-3 pl-7 pr-3 py-2.5 text-sm transition-colors cursor-pointer border-l-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 ${
                      active
                        ? 'border-l-primary-600 bg-primary-600/10 text-primary-600 font-medium'
                        : 'border-l-transparent text-muted hover:text-fg hover:bg-surface'
                    }`}
                  >
                    <Icon className="w-4 h-4" aria-hidden />
                    <span>{t.label}</span>
                  </button>
                );
              })}
          </nav>
          <div className="border-t border-border p-3">
            <Link
              to="/files"
              className="flex items-center gap-2 px-2 py-1.5 text-sm text-muted hover:text-fg transition-colors rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400"
            >
              <ArrowLeft className="w-4 h-4" aria-hidden />
              返回首页
            </Link>
          </div>
        </aside>

        <main className="flex-1 overflow-auto p-6">
          {tab === 'dashboard' && canShow('dashboard') && <DashboardTab />}
          {tab === 'users' && canShow('users') && <UserManageTab />}
          {tab === 'storage' && canShow('storage') && <StorageManageTab />}
          {tab === 'audit' && canShow('audit') && (
            <Suspense fallback={<div className="py-10 text-center text-sm text-muted">加载中…</div>}>
              <AuditLogPanel />
            </Suspense>
          )}
          {tab === 'speedLimit' && canShow('speedLimit') && <SpeedLimitPanel />}
          {tab === 'roles' && canShow('roles') && <RoleManagePanel />}
          {tab === 'security' && canShow('security') && <ShareSecurityPanel />}
        </main>
      </div>
    </div>
  );
}
