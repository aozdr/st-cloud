import { useState, useEffect } from 'react';
import { X, Link2, Lock, Globe, Copy, Download, RefreshCw } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import type { FileShare, CreateShareRequest } from '../../types';
import { PERMISSION_KEYS, legacyPermissionFromPerms } from '../../lib/permissions';
import QRCode from 'qrcode';

interface Props {
  fileNodeId: string;
  fileName: string;
  onClose: () => void;
}

export default function ShareDialog({ fileNodeId, fileName, onClose }: Props) {
  const { showToast } = useToast();
  const [shareType, setShareType] = useState(0);
  const [password, setPassword] = useState(() => generateShareCode());
  const [expirePreset, setExpirePreset] = useState(2); // 默认7天
  // 当前用户对该文件的有效权限集（后端 effective-permissions 接口，用于禁用超权项）
  const [effectivePerms, setEffectivePerms] = useState<Record<string, boolean>>({});
  // 本次分享勾选的权限点（默认 view+download；勾选 download → allowDownload=1）
  const [sharePerms, setSharePerms] = useState<Record<string, boolean>>({ view: true, download: true });
  const [permLoading, setPermLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [createdShare, setCreatedShare] = useState<FileShare | null>(null);
  const [qrUrl, setQrUrl] = useState('');

  function generateShareCode(): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 4; i++) code += chars[Math.floor(Math.random() * chars.length)];
    return code;
  }

  const EXPIRE_PRESETS = [
    { label: '1天', days: 1 },
    { label: '5天', days: 5 },
    { label: '7天', days: 7 },
    { label: '无限期', days: 0 },
  ];

  const computeExpireAt = (preset: number): string | undefined => {
    const { days } = EXPIRE_PRESETS[preset];
    if (days === 0) return undefined;
    const d = new Date();
    d.setDate(d.getDate() + days);
    // 提交本地时间（无时区后缀），与后端 LocalDateTime(Asia/Shanghai) 语义保持一致
    const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  };

  const handleCreate = async () => {
    if (shareType === 1 && !password) {
      showToast('私密分享请设置提取码', 'warning');
      return;
    }
    if (!Object.values(sharePerms).some(Boolean)) {
      showToast('请至少勾选一项分享权限', 'warning');
      return;
    }
    setLoading(true);
    try {
      const req: CreateShareRequest = {
        fileNodeId,
        shareType,
        password: shareType === 1 ? password : undefined,
        expireAt: computeExpireAt(expirePreset),
        // 权限点勾选结果写入 permissions（后端契约 String JSON，与角色管理提交格式一致）；
        // download 勾选 → allowDownload=1，与后端联动一致
        permissions: JSON.stringify(sharePerms),
        permission: legacyPermissionFromPerms(sharePerms),
        allowDownload: sharePerms.download ? 1 : 0,
      };
      const data: FileShare = await api.post('/share/create', req);
      setCreatedShare(data);
      showToast('分享创建成功', 'success');
    } catch (e) {
      showToast((e instanceof Error ? e.message : '') || '创建失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  const copyLink = () => {
    const pwdParam = createdShare!.shareType === 1 ? `?pwd=${password}` : '';
    const link = `${window.location.origin}/share/${createdShare!.shareCode}${pwdParam}`;
    navigator.clipboard.writeText(link);
    showToast('链接已复制', 'success');
  };

  const shareLink = createdShare
    ? `${window.location.origin}/share/${createdShare.shareCode}${createdShare.shareType === 1 ? `?pwd=${password}` : ''}`
    : '';

  useEffect(() => {
    if (shareLink) {
      QRCode.toDataURL(shareLink, { width: 160, margin: 1 })
        .then(setQrUrl)
        .catch(() => setQrUrl(''));
    } else {
      setQrUrl('');
    }
  }, [shareLink]);

  // 创建分享前加载当前用户对文件的有效权限集：不在集合内的权限点禁用（后端超权校验兜底）
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setPermLoading(true);
      try {
        const perms = await api.get<Record<string, boolean>>('/share/effective-permissions', { params: { fileNodeId } });
        if (cancelled) return;
        const effective = perms || {};
        setEffectivePerms(effective);
        // 默认勾选：view/download（仅当在有效权限集内），与后端默认分享权限一致
        setSharePerms(prev => ({ ...prev, view: Boolean(effective.view), download: Boolean(effective.download) }));
      } catch {
        if (!cancelled) setEffectivePerms({});
      } finally {
        if (!cancelled) setPermLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [fileNodeId]);

  const togglePerm = (key: string) => {
    setSharePerms(prev => {
      const next = { ...prev, [key]: !prev[key] };
      // 勾选 upload/download/edit 自动补 view；view 被依赖时不可取消（与后端隐含规则一致）
      if ((key === 'upload' || key === 'download' || key === 'edit') && next[key]) next.view = true;
      if (key === 'view' && !next.view && (next.upload || next.download || next.edit)) next.view = true;
      return next;
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div
        className="w-full max-w-md bg-surface rounded-xl shadow-lg border border-border overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
              <Link2 className="w-4 h-4 text-white" />
            </div>
            <h2 className="text-base font-semibold text-fg">分享文件</h2>
          </div>
          <button onClick={onClose} className="text-muted hover:text-fg transition-colors" aria-label="关闭">
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        {createdShare ? (
          /* Created share result */
          <div className="p-5 space-y-4">
            <div className="text-sm text-muted">
              文件 <span className="font-medium text-fg">{fileName}</span> 已创建分享
            </div>
            <div className="flex items-center gap-2 bg-surface-2 rounded-lg p-3">
              <input
                readOnly
                value={shareLink}
                className="flex-1 bg-transparent text-sm text-muted outline-none"
              />
              <button
                onClick={copyLink}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-primary-600 text-white text-xs font-medium rounded-lg hover:bg-primary-700 transition-colors cursor-pointer"
              >
                <Copy className="w-3.5 h-3.5" aria-hidden />
                复制
              </button>
            </div>
            {createdShare.shareType === 1 && (
              <div className="flex items-center gap-2 text-sm text-amber-600 dark:text-amber-400 bg-amber-500/15 rounded-lg p-3">
                <Lock className="w-4 h-4 flex-shrink-0" />
                <span>提取码：{password}</span>
              </div>
            )}
            {qrUrl && (
              <div className="flex flex-col items-center gap-2">
                <img src={qrUrl} alt="QR Code" width={144} height={144} loading="lazy" className="w-36 h-36 rounded-lg border border-border" />
                <span className="text-xs text-muted">{'\u626b\u7801\u8bbf\u95ee'}</span>
              </div>
            )}
            <div className="flex items-center gap-2 text-xs text-muted">
              {createdShare.expireAt && <span>有效期至 {createdShare.expireAt}</span>}
              <span className="inline-flex items-center gap-1">
                <Download className="w-3.5 h-3.5" aria-hidden />
                {createdShare.allowDownload === 1 ? '允许下载' : '仅查看'}
              </span>
            </div>
            <button
              onClick={onClose}
              className="w-full py-2.5 bg-surface-2 text-muted text-sm font-medium rounded-lg hover:bg-surface-2 transition-colors cursor-pointer"
            >
              完成
            </button>
          </div>
        ) : (
          /* Create share form */
          <div className="p-5 space-y-4">
            <div className="text-sm text-muted truncate">文件：{fileName}</div>

            {/* Share type */}
            <div>
              <label className="text-xs font-medium text-muted mb-2 block">分享方式</label>
              <div className="flex gap-2">
                <button
                  onClick={() => setShareType(0)}
                  className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition cursor-pointer ${
                    shareType === 0
                      ? 'bg-primary-500/10 text-primary-600 ring-1 ring-primary-200'
                      : 'bg-surface-2 text-muted hover:bg-surface-2'
                  }`}
                >
                  <Globe className="w-4 h-4" aria-hidden />
                  公开
                </button>
                <button
                  onClick={() => setShareType(1)}
                  className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition cursor-pointer ${
                    shareType === 1
                      ? 'bg-amber-500/15 text-amber-700 ring-1 ring-amber-200'
                      : 'bg-surface-2 text-muted hover:bg-surface-2'
                  }`}
                >
                  <Lock className="w-4 h-4" aria-hidden />
                  提取码
                </button>
              </div>
            </div>

            {/* Password */}
            {shareType === 1 && (
              <div>
                <label className="text-xs font-medium text-muted mb-1.5 block">提取码</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={password}
                    onChange={(e) => setPassword(e.target.value.toUpperCase().slice(0, 8))}
                    placeholder="设置提取码"
                    className="flex-1 px-3 py-2 text-sm font-mono tracking-widest bg-surface-2 rounded-lg border border-border outline-none focus:border-primary-400 focus:bg-surface transition-colors"
                  />
                  <button
                    onClick={() => setPassword(generateShareCode())}
                    className="flex items-center gap-1 px-3 py-2 text-xs text-muted bg-surface-2 rounded-lg border border-border hover:bg-surface-2 hover:text-fg transition-colors cursor-pointer whitespace-nowrap"
                  >
                    <RefreshCw className="w-3.5 h-3.5" aria-hidden />
                    换一个
                  </button>
                </div>
              </div>
            )}

            {/* 分享权限点：展示可分享权限点，不在当前用户有效权限集内的项禁用（后端超权校验兜底） */}
            <div>
              <label className="text-xs font-medium text-muted mb-2 block">分享权限</label>
              <div className="grid grid-cols-2 gap-2">
                {PERMISSION_KEYS.map(p => {
                  const enabled = Boolean(effectivePerms[p.key]);
                  return (
                    <label
                      key={p.key}
                      className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm cursor-pointer ${enabled ? 'bg-surface-2' : 'bg-surface-2/60 opacity-60 cursor-not-allowed'}`}
                    >
                      <input
                        type="checkbox"
                        checked={Boolean(sharePerms[p.key])}
                        disabled={!enabled || permLoading}
                        onChange={() => togglePerm(p.key)}
                        className="cursor-pointer disabled:cursor-not-allowed"
                      />
                      <span className={enabled ? 'text-fg' : 'text-muted'}>{p.label}</span>
                    </label>
                  );
                })}
              </div>
              <p className="text-xs text-muted mt-1.5">勾选「上传文件」或「下载文件」自动包含「查看文件」；勾选「下载文件」时允许下载/流式（与后端联动）</p>
            </div>

            {/* Expiry */}
            <div>
              <label className="text-xs font-medium text-muted mb-1.5 block">有效期</label>
              <div className="flex gap-2">
                {EXPIRE_PRESETS.map((preset, idx) => (
                  <button
                    key={idx}
                    onClick={() => setExpirePreset(idx)}
                    className={`flex-1 py-2 rounded-lg text-xs font-medium transition cursor-pointer ${
                      expirePreset === idx
                        ? 'bg-primary-500/10 text-primary-600 ring-1 ring-primary-200'
                        : 'bg-surface-2 text-muted hover:bg-surface-2'
                    }`}
                  >
                    {preset.label}
                  </button>
                ))}
              </div>
            </div>

            <button
              onClick={handleCreate}
              disabled={loading}
              className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50"
            >
              {loading ? '创建中…' : '创建分享'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
