import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams, Link } from 'react-router-dom';
import { Cloud, Lock, User, Mail, Building2, ArrowRight } from 'lucide-react';
import { useAuthStore } from '../store/auth';
import { isElectron } from '../lib/electron';
import type { LoginRequest, RegisterRequest } from '../types';
import TitleBar from '../components/layout/TitleBar';

export default function Login() {
  const login = useAuthStore((s) => s.login);
  const register = useAuthStore((s) => s.register);
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [form, setForm] = useState({
    username: '',
    password: '',
    email: '',
    tenantName: '',
  });

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (mode === 'login') {
        const req: LoginRequest = {
          username: form.username,
          password: form.password,
        };
        await login(req);
      } else {
        const req: RegisterRequest = {
          username: form.username,
          password: form.password,
          email: form.email || undefined,
          tenantName: form.tenantName || undefined,
        };
        await register(req);
      }
      // 支持登录后跳转回原页面（如分享页保存流程：/share/xxx?save=1）
      const redirect = searchParams.get('redirect');
      navigate(redirect || '/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex flex-col min-h-screen">
      {/* 蓝色标题栏仅 Electron 桌面端渲染（网页端不显示） */}
      {isElectron() && <TitleBar />}
      <div className="flex flex-1 min-h-0">
        {/* Left brand panel */}
        <div className="hidden lg:flex lg:w-[45%] brand-gradient flex-col justify-between p-12 relative overflow-hidden">
        <div className="relative z-10">
          <div className="flex items-center gap-3 text-fg">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-700 rounded-xl flex items-center justify-center shadow-primary">
              <Cloud className="w-6 h-6 text-white" aria-hidden />
            </div>
            <span className="text-xl font-semibold tracking-tight">星云盘</span>
          </div>
        </div>

        <div className="relative z-10 text-fg">
          <h1 className="text-5xl font-bold leading-tight mb-4 bg-gradient-to-r from-fg to-muted bg-clip-text text-transparent">
            安全、高效的<br />企业云盘解决方案
          </h1>
          <p className="text-muted text-base leading-relaxed max-w-md mt-6">
            支持大文件分片上传、秒传去重、多格式预览，<br />
            让文件管理像本地操作一样流畅。
          </p>
          <div className="mt-10 flex flex-col gap-3 text-muted text-sm">
            <div className="flex items-center gap-2.5">
              <div className="w-2 h-2 rounded-full bg-primary-500 shadow-[0_0_8px_rgb(var(--color-primary-500)/0.6)]" />
              <span>分片上传 · 断点续传</span>
            </div>
            <div className="flex items-center gap-2.5">
              <div className="w-2 h-2 rounded-full bg-primary-500 shadow-[0_0_8px_rgb(var(--color-primary-500)/0.6)]" />
              <span>MD5 秒传</span>
            </div>
          </div>
        </div>

        <div className="relative z-10 text-muted text-sm">
          © 2026 星云盘. All rights reserved.
        </div>
      </div>

      {/* Right form panel */}
      <div className="flex-1 flex items-center justify-center px-6 py-12 bg-bg">
        <div className="w-full max-w-md">
          <div className="lg:hidden flex items-center gap-3 mb-8">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-700 rounded-xl flex items-center justify-center shadow-primary">
              <Cloud className="w-6 h-6 text-white" aria-hidden />
            </div>
            <h1 className="text-xl font-semibold text-fg">星云盘</h1>
          </div>

          <h2 className="text-2xl font-semibold text-fg mb-2">
            {mode === 'login' ? '欢迎回来' : '创建账户'}
          </h2>
          <p className="text-muted text-sm mb-8">
            {mode === 'login'
              ? '登录你的云盘账户继续使用'
              : '注册新账户，开始管理你的文件'}
          </p>

          {error && (
            <div className="mb-4 px-4 py-3 bg-red-500/15 border border-red-200 rounded-xl text-sm text-red-700 animate-slide-up shadow-soft">
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label htmlFor="login-username" className="block text-sm font-medium text-muted mb-1.5">用户名</label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" aria-hidden />
                <input
                  id="login-username"
                  name="username"
                  type="text"
                  required
                  autoComplete="username"
                  spellCheck={false}
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  className="input-field pl-10"
                  placeholder="请输入用户名"
                />
              </div>
            </div>

            <div>
              <label htmlFor="login-password" className="block text-sm font-medium text-muted mb-1.5">密码</label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" aria-hidden />
                <input
                  id="login-password"
                  name="password"
                  type="password"
                  required
                  autoComplete="current-password"
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  className="input-field pl-10"
                  placeholder="请输入密码"
                  minLength={6}
                />
              </div>
            </div>

            {mode === 'register' && (
              <>
                <div className="animate-slide-up">
                  <label htmlFor="login-email" className="block text-sm font-medium text-muted mb-1.5">邮箱（选填）</label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" aria-hidden />
                    <input
                      id="login-email"
                      name="email"
                      type="email"
                      autoComplete="email"
                      spellCheck={false}
                      value={form.email}
                      onChange={(e) => setForm({ ...form, email: e.target.value })}
                      className="input-field pl-10"
                      placeholder="name@example.com"
                    />
                  </div>
                </div>
                <div className="animate-slide-up">
                  <label htmlFor="login-tenant" className="block text-sm font-medium text-muted mb-1.5">组织名称（选填）</label>
                  <div className="relative">
                    <Building2 className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" aria-hidden />
                    <input
                      id="login-tenant"
                      name="tenantName"
                      type="text"
                      autoComplete="organization"
                      value={form.tenantName}
                      onChange={(e) => setForm({ ...form, tenantName: e.target.value })}
                      className="input-field pl-10"
                      placeholder="公司或团队名称"
                    />
                  </div>
                </div>
              </>
            )}

            <button
              type="submit"
              disabled={loading}
              className="btn-primary w-full justify-center py-2.5"
            >
              {loading ? (
                <span className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
              ) : (
                <>
                  {mode === 'login' ? '登录' : '注册'}
                  <ArrowRight className="w-4 h-4" aria-hidden />
                </>
              )}
            </button>
          </form>

          <div className="mt-6 text-center text-sm text-muted">
            {mode === 'login' ? '还没有账户？' : '已有账户？'}
            <button
              onClick={() => {
                setMode(mode === 'login' ? 'register' : 'login');
                setError('');
              }}
              className="ml-1 text-primary-600 hover:text-primary-600 font-medium cursor-pointer"
            >
              {mode === 'login' ? '立即注册' : '返回登录'}
            </button>
          </div>

          {isElectron() && (
            <div className="mt-4 text-center">
              <Link to="/server-config" className="text-xs text-muted hover:text-primary-600 cursor-pointer">
                服务器设置
              </Link>
            </div>
          )}
        </div>
      </div>
      </div>
    </div>
  );
}
