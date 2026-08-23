import { memo, useState, useEffect, useCallback } from 'react';
import { Save, RefreshCw, Shield, Link2, MapPin, Info, type LucideIcon } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import type { SysConfig } from '../../types';

const LABELS: Record<string, string> = {
  shareCodeLength: '分享码长度',
  maxFailPerCode: '单分享码失败阈值',
  codeWindowMs: '单码失败窗口 (ms)',
  codeLockMs: '单码锁定 (ms)',
  maxFailPerIp: '单IP总失败阈值',
  ipWindowMs: '单IP失败窗口 (ms)',
  ipLockMs: '单IP锁定 (ms)',
  captchaEnabled: '启用验证码',
  captchaThreshold: '验证码触发阈值',
  captchaLockMs: '验证码失败锁定 (ms)',
};

/** 带单位后缀的字段配置 */
const UNIT_FIELDS = new Set(['codeWindowMs', 'codeLockMs', 'ipWindowMs', 'ipLockMs', 'captchaLockMs']);

/** 按语义分组：与设计稿一致（分享码/单码限制、单 IP 限制、验证码） */
const GROUPS = [
  {
    key: 'code',
    icon: Link2 as LucideIcon,
    title: '分享码 / 单码限制',
    hint: '分享码长度与单个分享码的失败锁定',
    test: (s: string) => s === 'shareCodeLength' || s.startsWith('code'),
    order: ['codeLockMs', 'codeWindowMs', 'shareCodeLength'],
  },
  {
    key: 'ip',
    icon: MapPin as LucideIcon,
    title: '单 IP 限制',
    hint: '按客户端 IP 维度限制失败次数与锁定',
    test: (s: string) => s.startsWith('ip'),
    order: ['ipLockMs', 'ipWindowMs'],
  },
  {
    key: 'captcha',
    icon: Shield as LucideIcon,
    title: '验证码',
    hint: '失败达到阈值后做人机验证',
    test: (s: string) => s.startsWith('captcha'),
    order: ['captchaEnabled', 'captchaLockMs', 'captchaThreshold'],
  },
] as const;

function ShareSecurityPanel() {
  const { showToast } = useToast();
  const [configs, setConfigs] = useState<SysConfig[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data: SysConfig[] = await api.get('/admin/config/share-security');
      setConfigs(data || []);
      const v: Record<string, string> = {};
      (data || []).forEach((c) => {
        v[c.configKey] = c.configValue ?? '';
      });
      setValues(v);
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  const suffixOf = (key: string) => key.split('share.brute_force.').pop() || key;
  const labelOf = (key: string) => {
    const suffix = suffixOf(key);
    return LABELS[suffix] || suffix;
  };
  const isBool = (key: string) => key.endsWith('.captchaEnabled');
  const unitOf = (key: string) => (UNIT_FIELDS.has(suffixOf(key)) ? 'ms' : null);

  const handleSave = async () => {
    setSaving(true);
    try {
      await Promise.all(
        configs.map((c) =>
          api.put('/admin/config/share-security', {
            key: c.configKey,
            value: String(values[c.configKey] ?? ''),
          }),
        ),
      );
      showToast('配置已保存', 'success');
      load();
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const ordered = (items: SysConfig[], order: readonly string[]) => {
    const byKey = new Map(items.map((c) => [suffixOf(c.configKey), c]));
    const sorted: SysConfig[] = [];
    order.forEach((k) => {
      const c = byKey.get(k);
      if (c) sorted.push(c);
    });
    // 未列出的（如 maxFailPerCode / maxFailPerIp 若后端仍返回）追加到末尾
    items.forEach((c) => {
      if (!order.includes(suffixOf(c.configKey))) sorted.push(c);
    });
    return sorted;
  };

  const field = (c: SysConfig) => {
    const unit = unitOf(c.configKey);
    return (
      <div key={c.id} className="flex flex-col gap-0.5">
        <label htmlFor={`cfg-${c.configKey}`} className="text-sm text-fg">
          {labelOf(c.configKey)}
        </label>
        {c.remark && <p className="text-xs text-muted">{c.remark}</p>}
        {isBool(c.configKey) ? (
          <button
            type="button"
            role="switch"
            aria-checked={values[c.configKey] === 'true'}
            aria-label={labelOf(c.configKey)}
            onClick={() =>
              setValues((prev) => ({
                ...prev,
                [c.configKey]: prev[c.configKey] === 'true' ? 'false' : 'true',
              }))
            }
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 ${values[c.configKey] === 'true' ? 'bg-primary-600' : 'bg-stone-300'}`}
          >
            <span
              className={`inline-block h-5 w-5 rounded-full bg-white shadow transition-transform ${values[c.configKey] === 'true' ? 'translate-x-5' : 'translate-x-1'}`}
            />
          </button>
        ) : (
          <div className="relative">
            <input
              id={`cfg-${c.configKey}`}
              type="number"
              value={values[c.configKey] ?? ''}
              onChange={(e) =>
                setValues((prev) => ({
                  ...prev,
                  [c.configKey]: e.target.value,
                }))
              }
              className={`w-full max-w-[240px] px-3 py-1 text-sm border border-border rounded-md bg-surface focus:outline-none focus:border-primary-500 focus-visible:ring-2 focus-visible:ring-primary-100 ${unit ? 'pr-12' : ''}`}
            />
            {unit && (
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted pointer-events-none">
                {unit}
              </span>
            )}
          </div>
        )}
      </div>
    );
  };

  const card = (g: (typeof GROUPS)[number]) => {
    const items = ordered(configs.filter((c) => g.test(suffixOf(c.configKey))), g.order);
    const Icon = g.icon;
    const cols = g.key === 'captcha' ? 'sm:grid-cols-3' : 'sm:grid-cols-2';
    return (
      <section key={g.key} className="border border-border/60 rounded-2xl bg-white dark:bg-surface p-3 shadow-[0_4px_16px_rgba(15,23,42,0.06)]">
        <div className="flex items-center gap-2">
          <Icon className="w-4 h-4 text-primary-600" aria-hidden />
          <h3 className="text-base font-semibold text-fg">{g.title}</h3>
        </div>
        <p className="text-sm text-muted mt-0.5 mb-2">{g.hint}</p>
        <div className={`grid grid-cols-1 gap-3 ${cols}`}>{items.map(field)}</div>
      </section>
    );
  };

  return (
    <div className="flex flex-col gap-4">
      {/* 顶部标题 + 操作 */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-semibold text-fg">
            <Shield className="w-5 h-5 text-primary-600" aria-hidden />
            分享安全
          </h2>
          <p className="text-sm text-muted mt-0.5">
            分享防爆破与提取码安全参数，保存后即时生效（多实例由 Redis 同步，单实例内存兜底）。
          </p>
        </div>
        <div className="flex gap-2 flex-shrink-0">
          <button
            type="button"
            onClick={load}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-stone-600 bg-white border border-border rounded-md hover:bg-stone-50 transition-colors cursor-pointer disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400"
          >
            <RefreshCw className="w-4 h-4" aria-hidden />
            刷新
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-1.5 px-4 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-400"
          >
            <Save className="w-4 h-4" aria-hidden />
            {saving ? '保存中…' : '保存配置'}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="py-10 text-center text-sm text-muted">加载中…</div>
      ) : (
        <>
          {/* 上排两张卡片并排：分享码/单码限制 + 单 IP 限制 */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {GROUPS.slice(0, 2).map(card)}
          </div>
          {/* 验证码通栏卡片 */}
          {card(GROUPS[2])}

          {/* 配置说明 */}
          <div className="border border-sky-100 bg-sky-50/60 rounded-2xl p-2.5">
            <div className="flex items-center gap-2 text-sm font-medium text-sky-800">
              <Info className="w-4 h-4" aria-hidden />
              配置说明
            </div>
            <ul className="mt-1 space-y-0.5 text-xs text-sky-700">
              <li>所有配置保存后立即生效，无需重启服务。</li>
              <li>多实例环境下通过 Redis 同步配置，单实例使用内存兜底。</li>
              <li>建议根据实际业务情况调整参数。过于严格的限制可能影响正常用户体验。</li>
            </ul>
          </div>
        </>
      )}
    </div>
  );
}

export default memo(ShareSecurityPanel);
