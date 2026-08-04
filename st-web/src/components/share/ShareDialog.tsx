import { useState } from 'react';
import { X, Link2, Lock, Globe, Copy, Download, Shield, RefreshCw } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import type { FileShare, CreateShareRequest } from '../../types';

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
  const [permission, setPermission] = useState(0);
  const [loading, setLoading] = useState(false);
  const [createdShare, setCreatedShare] = useState<FileShare | null>(null);

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
    return d.toISOString();
  };

  const handleCreate = async () => {
    if (shareType === 1 && !password) {
      showToast('私密分享请设置提取码', 'warning');
      return;
    }
    setLoading(true);
    try {
      const req: CreateShareRequest = {
        fileNodeId,
        shareType,
        password: shareType === 1 ? password : undefined,
        expireAt: computeExpireAt(expirePreset),
        permission,
      };
      const data: FileShare = await api.post('/share/create', req);
      setCreatedShare(data);
      showToast('分享创建成功', 'success');
    } catch (e: any) {
      showToast(e.message || '创建失败', 'error');
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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-900/40" onClick={onClose}>
      <div
        className="w-full max-w-md bg-white rounded-xl shadow-lg border border-stone-200 overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-stone-100">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
              <Link2 className="w-4 h-4 text-white" />
            </div>
            <h2 className="text-base font-semibold text-stone-900">分享文件</h2>
          </div>
          <button onClick={onClose} className="text-stone-400 hover:text-stone-600 transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        {createdShare ? (
          /* Created share result */
          <div className="p-5 space-y-4">
            <div className="text-sm text-stone-600">
              文件 <span className="font-medium text-stone-900">{fileName}</span> 已创建分享
            </div>
            <div className="flex items-center gap-2 bg-stone-100 rounded-lg p-3">
              <input
                readOnly
                value={shareLink}
                className="flex-1 bg-transparent text-sm text-stone-700 outline-none"
              />
              <button
                onClick={copyLink}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-primary-600 text-white text-xs font-medium rounded-lg hover:bg-primary-700 transition-colors cursor-pointer"
              >
                <Copy className="w-3.5 h-3.5" />
                复制
              </button>
            </div>
            {createdShare.shareType === 1 && (
              <div className="flex items-center gap-2 text-sm text-amber-600 bg-amber-50 rounded-lg p-3">
                <Lock className="w-4 h-4 flex-shrink-0" />
                <span>提取码：{password}</span>
              </div>
            )}
            <div className="flex items-center gap-2 text-xs text-stone-400">
              {createdShare.expireAt && <span>有效期至 {createdShare.expireAt}</span>}
            </div>
            <button
              onClick={onClose}
              className="w-full py-2.5 bg-stone-100 text-stone-700 text-sm font-medium rounded-lg hover:bg-stone-200 transition-colors cursor-pointer"
            >
              完成
            </button>
          </div>
        ) : (
          /* Create share form */
          <div className="p-5 space-y-4">
            <div className="text-sm text-stone-500 truncate">文件：{fileName}</div>

            {/* Share type */}
            <div>
              <label className="text-xs font-medium text-stone-500 mb-2 block">分享方式</label>
              <div className="flex gap-2">
                <button
                  onClick={() => setShareType(0)}
                  className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition-all cursor-pointer ${
                    shareType === 0
                      ? 'bg-primary-50 text-primary-700 ring-1 ring-primary-200'
                      : 'bg-stone-50 text-stone-500 hover:bg-stone-100'
                  }`}
                >
                  <Globe className="w-4 h-4" />
                  公开
                </button>
                <button
                  onClick={() => setShareType(1)}
                  className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition-all cursor-pointer ${
                    shareType === 1
                      ? 'bg-amber-50 text-amber-700 ring-1 ring-amber-200'
                      : 'bg-stone-50 text-stone-500 hover:bg-stone-100'
                  }`}
                >
                  <Lock className="w-4 h-4" />
                  提取码
                </button>
              </div>
            </div>

            {/* Password */}
            {shareType === 1 && (
              <div>
                <label className="text-xs font-medium text-stone-500 mb-1.5 block">提取码</label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={password}
                    onChange={(e) => setPassword(e.target.value.toUpperCase().slice(0, 8))}
                    placeholder="设置提取码"
                    className="flex-1 px-3 py-2 text-sm font-mono tracking-widest bg-stone-50 rounded-lg border border-stone-200 outline-none focus:border-primary-400 focus:bg-white transition-colors"
                  />
                  <button
                    onClick={() => setPassword(generateShareCode())}
                    className="flex items-center gap-1 px-3 py-2 text-xs text-stone-500 bg-stone-50 rounded-lg border border-stone-200 hover:bg-stone-100 hover:text-stone-700 transition-colors cursor-pointer whitespace-nowrap"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    换一个
                  </button>
                </div>
              </div>
            )}

            {/* Permission */}
            <div>
              <label className="text-xs font-medium text-stone-500 mb-1.5 block">权限</label>
              <div className="flex gap-2">
                {[
                  { v: 0, label: '仅查看', icon: Shield },
                  { v: 1, label: '可下载', icon: Download },
                ].map((opt) => (
                  <button
                    key={opt.v}
                    onClick={() => setPermission(opt.v)}
                    className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                      permission === opt.v
                        ? 'bg-primary-50 text-primary-700 ring-1 ring-primary-200'
                        : 'bg-stone-50 text-stone-500 hover:bg-stone-100'
                    }`}
                  >
                    <opt.icon className="w-3.5 h-3.5" />
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Expiry */}
            <div>
              <label className="text-xs font-medium text-stone-500 mb-1.5 block">有效期</label>
              <div className="flex gap-2">
                {EXPIRE_PRESETS.map((preset, idx) => (
                  <button
                    key={idx}
                    onClick={() => setExpirePreset(idx)}
                    className={`flex-1 py-2 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                      expirePreset === idx
                        ? 'bg-primary-50 text-primary-700 ring-1 ring-primary-200'
                        : 'bg-stone-50 text-stone-500 hover:bg-stone-100'
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
              {loading ? '创建中...' : '创建分享'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
