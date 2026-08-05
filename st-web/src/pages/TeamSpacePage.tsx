import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Users, Plus, X, UserPlus, Crown, Pencil, Eye, Upload, ChevronRight, Settings, HardDrive } from 'lucide-react';
import api from '../lib/api';
import FileBrowser from '../components/file/FileBrowser';
import { teamFileSource } from '../lib/fileSource';
import { useToast } from '../components/ui/Toast';
import { useUpload } from '../hooks/useUpload';
import { formatSize, cn } from '../lib/utils';
import { usePermission } from '../lib/permission';
import type { TeamSpace, TeamMember, FileNode, PageResult } from '../types';

export default function TeamSpacePage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const { addFiles } = useUpload();
  const { has } = usePermission();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const source = useMemo(() => teamFileSource(spaceId!), [spaceId]);

  const [space, setSpace] = useState<TeamSpace | null>(null);
  const [parentId, setParentId] = useState<string | null>(null);
  const [breadcrumbs, setBreadcrumbs] = useState<{ id: string; name: string }[]>([]);

  const [showMembers, setShowMembers] = useState(false);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [inviteUsername, setInviteUsername] = useState('');
  const [inviteRole, setInviteRole] = useState(2);
  const [showSettings, setShowSettings] = useState(false);
  const [quotaInput, setQuotaInput] = useState('');
  const [quotaUnit, setQuotaUnit] = useState<'MB' | 'GB' | '0'>('GB');
  const [settingsLoading, setSettingsLoading] = useState(false);

  const fetchSpace = useCallback(async () => {
    try {
      const data: TeamSpace = await api.get(`/team/${spaceId}`);
      setSpace(data);
    } catch { /* ignore */ }
  }, [spaceId]);

  const fetchMembers = useCallback(async () => {
    try {
      const res = await api.get<PageResult<TeamMember>>(`/team/${spaceId}/members`, { params: { page: 1, size: 50 } });
      setMembers(res?.records || []);
    } catch { /* ignore */ }
  }, [spaceId]);

  useEffect(() => { fetchSpace(); }, [fetchSpace]);

  const handleInvite = async () => {
    if (!inviteUsername.trim()) return;
    try {
      await api.post(`/team/${spaceId}/member`, { username: inviteUsername.trim(), role: inviteRole });
      showToast('邀请成功', 'success');
      setInviteUsername('');
      fetchMembers();
    } catch (e: any) {
      showToast(e.message || '邀请失败', 'error');
    }
  };

  const handleRemoveMember = async (memberId: string) => {
    if (!confirm('确定移除该成员？')) return;
    try {
      await api.delete(`/team/${spaceId}/member/${memberId}`);
      showToast('成员已移除', 'success');
      fetchMembers();
    } catch (e: any) {
      showToast(e.message || '操作失败', 'error');
    }
  };

  const openSettings = () => {
    const quota = Number(space?.storageQuota || 0);
    if (quota === 0) {
      setQuotaInput('');
      setQuotaUnit('0');
    } else if (quota >= 1024 * 1024 * 1024) {
      setQuotaInput(String((quota / (1024 * 1024 * 1024)).toFixed(1)));
      setQuotaUnit('GB');
    } else {
      setQuotaInput(String((quota / (1024 * 1024)).toFixed(0)));
      setQuotaUnit('MB');
    }
    setShowSettings(true);
  };

  const handleSaveSettings = async () => {
    let quotaBytes = 0;
    if (quotaUnit !== '0') {
      const val = parseFloat(quotaInput);
      if (isNaN(val) || val <= 0) {
        showToast('请输入有效的配额数值', 'error');
        return;
      }
      quotaBytes = quotaUnit === 'GB' ? Math.round(val * 1024 * 1024 * 1024) : Math.round(val * 1024 * 1024);
    }
    setSettingsLoading(true);
    try {
      await api.put(`/team/${spaceId}`, { storageQuota: quotaBytes });
      showToast('存储配额已更新', 'success');
      setShowSettings(false);
      fetchSpace();
    } catch (e: any) {
      showToast(e.message || '更新失败', 'error');
    } finally {
      setSettingsLoading(false);
    }
  };

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    if (selected.length > 0) {
      addFiles(selected, parentId || '0', undefined, spaceId);
    }
    e.target.value = '';
  };

  const navigateToFolder = (node: FileNode) => {
    if (!node.id || node.id === '0') {
      setBreadcrumbs([]);
      setParentId(null);
      return;
    }
    setBreadcrumbs((prev) => [...prev, { id: node.id, name: node.name }]);
    setParentId(node.id);
  };

  const navigateToCrumb = (idx: number) => {
    if (idx === -1) {
      setBreadcrumbs([]);
      setParentId(null);
    } else {
      setBreadcrumbs(breadcrumbs.slice(0, idx + 1));
      setParentId(breadcrumbs[idx].id);
    }
  };

  const handleBack = () => {
    if (breadcrumbs.length > 0) {
      navigateToCrumb(breadcrumbs.length - 2);
    } else {
      navigate('/team');
    }
  };

  const usedPercent = space ? (Number(space.storageQuota) > 0 ? Math.min(100, (Number(space.storageUsed) / Number(space.storageQuota)) * 100) : 0) : 0;

  const roleConfig = [
    { label: '管理员', icon: Crown, color: 'text-amber-600 bg-amber-50' },
    { label: '编辑者', icon: Pencil, color: 'text-primary-600 bg-primary-50' },
    { label: '查看者', icon: Eye, color: 'text-stone-600 bg-stone-100' },
  ];

  return (
    <div className="flex flex-col h-full">
      <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleFileInputChange} />

      {/* Header */}
      <div className="flex items-center justify-between px-6 py-3 border-b border-stone-200 bg-white">
        <div className="flex items-center gap-3 min-w-0">
          <button onClick={() => navigate('/team')} className="text-stone-400 hover:text-stone-600 cursor-pointer flex-shrink-0">
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center flex-shrink-0">
            <Users className="w-4 h-4 text-white" />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-stone-900 truncate">{space?.spaceName || '加载中...'}</h1>
            {space?.description && <p className="text-xs text-stone-500 truncate">{space.description}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <button
            onClick={() => { setShowMembers(true); fetchMembers(); }}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-stone-600 bg-stone-100 rounded-md hover:bg-stone-200 transition-colors cursor-pointer"
          >
            <Users className="w-4 h-4" />
            <span className="hidden sm:inline">成员</span>
            {space?.memberCount !== undefined && <span className="text-xs text-stone-400">{space.memberCount}</span>}
          </button>
          <button
            onClick={openSettings}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-stone-600 bg-stone-100 rounded-md hover:bg-stone-200 transition-colors cursor-pointer"
            title="空间设置"
          >
            <Settings className="w-4 h-4" />
            <span className="hidden sm:inline">设置</span>
          </button>
          {has('file:upload') && (
            <button
              onClick={() => fileInputRef.current?.click()}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer"
            >
              <Upload className="w-4 h-4" />
              <span className="hidden sm:inline">上传</span>
            </button>
          )}
        </div>
      </div>

      {/* Storage indicator */}
      {space && (
        <div
          className="flex items-center gap-2 px-6 py-1.5 border-b border-stone-100 bg-stone-50/50 text-xs text-stone-400 cursor-pointer hover:bg-stone-100/50 transition-colors"
          onClick={openSettings}
          title="点击管理存储配额"
        >
          <HardDrive className="w-3.5 h-3.5" />
          <span className="flex-1">存储: {formatSize(Number(space.storageUsed))} / {Number(space.storageQuota) > 0 ? formatSize(Number(space.storageQuota)) : '不限制'}</span>
          <div className="w-32 h-1.5 bg-stone-200 rounded-full overflow-hidden">
            <div className={cn('h-full rounded-full transition-all', usedPercent > 90 ? 'bg-red-500' : usedPercent > 70 ? 'bg-amber-500' : 'bg-primary-500')} style={{ width: `${usedPercent}%` }} />
          </div>
          <span className="text-stone-400">{usedPercent > 0 ? `${Math.round(usedPercent)}%` : ''}</span>
        </div>
      )}

      {/* File browser (reuses normal file logic via FileSource adapter) */}
      <div className="flex-1 min-h-0 overflow-hidden">
        <FileBrowser
          key={parentId || "root"}
          source={source}
          parentId={parentId}
          onNavigateFolder={navigateToFolder}
          onBack={handleBack}
          uploadSpaceId={spaceId}
          enableShare={false}
          enableVersions={false}
        />
      </div>

      {/* Storage settings dialog */}
      {showSettings && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-900/40" onClick={() => setShowSettings(false)}>
          <div className="w-full max-w-md bg-white rounded-xl shadow-lg border border-stone-200 overflow-hidden animate-scale-in" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-stone-100">
              <div className="flex items-center gap-2">
                <HardDrive className="w-4 h-4 text-primary-600" />
                <h2 className="text-base font-semibold text-stone-900">存储管理</h2>
              </div>
              <button onClick={() => setShowSettings(false)} className="text-stone-400 hover:text-stone-600 cursor-pointer"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-5 space-y-4">
              {/* Current usage */}
              <div className="flex items-center gap-3 p-3 bg-stone-50 rounded-lg">
                <div className="w-10 h-10 bg-primary-100 rounded-lg flex items-center justify-center">
                  <HardDrive className="w-5 h-5 text-primary-600" />
                </div>
                <div>
                  <p className="text-sm font-medium text-stone-800">当前已用 {formatSize(Number(space?.storageUsed || 0))}</p>
                  <p className="text-xs text-stone-400">{Number(space?.storageQuota) > 0 ? `配额 ${formatSize(Number(space?.storageQuota))}` : '无配额限制'}</p>
                </div>
              </div>
              {/* Quota input */}
              <div>
                <label className="block text-sm font-medium text-stone-700 mb-2">存储配额</label>
                <div className="flex gap-2">
                  <input
                    type="number"
                    value={quotaInput}
                    onChange={(e) => setQuotaInput(e.target.value)}
                    disabled={quotaUnit === '0'}
                    placeholder={quotaUnit === '0' ? '不限制' : '输入数值'}
                    className="flex-1 px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none focus:border-primary-400 focus:bg-white transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  />
                  <select
                    value={quotaUnit}
                    onChange={(e) => { setQuotaUnit(e.target.value as any); if (e.target.value === '0') setQuotaInput(''); }}
                    className="px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none cursor-pointer"
                  >
                    <option value="MB">MB</option>
                    <option value="GB">GB</option>
                    <option value="0">不限制</option>
                  </select>
                </div>
                <p className="text-xs text-stone-400 mt-1.5">设为「不限制」则该空间存储无上限</p>
              </div>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3 border-t border-stone-100">
              <button onClick={() => setShowSettings(false)} className="btn-secondary">取消</button>
              <button onClick={handleSaveSettings} disabled={settingsLoading} className="btn-primary">
                {settingsLoading ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Member management dialog */}
      {showMembers && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-900/40" onClick={() => setShowMembers(false)}>
          <div className="w-full max-w-lg bg-white rounded-xl shadow-lg border border-stone-200 overflow-hidden animate-scale-in" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-stone-100">
              <h2 className="text-base font-semibold text-stone-900">成员管理</h2>
              <button onClick={() => setShowMembers(false)} className="text-stone-400 hover:text-stone-600 cursor-pointer"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-5 space-y-4">
              <div className="flex gap-2">
                <input type="text" value={inviteUsername} onChange={(e) => setInviteUsername(e.target.value)} placeholder="用户名" className="flex-1 px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none focus:border-primary-400 focus:bg-white transition-colors" />
                <select value={inviteRole} onChange={(e) => setInviteRole(parseInt(e.target.value))} className="px-3 py-2 text-sm bg-stone-50 rounded-md border border-stone-200 outline-none cursor-pointer">
                  <option value={0}>管理员</option>
                  <option value={1}>编辑者</option>
                  <option value={2}>查看者</option>
                </select>
                <button onClick={handleInvite} className="flex items-center gap-1 px-4 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer whitespace-nowrap">
                  <UserPlus className="w-4 h-4" />
                  邀请
                </button>
              </div>
              <div className="space-y-2 max-h-80 overflow-auto">
                {members.map((member) => {
                  const role = roleConfig[member.role] || roleConfig[2];
                  return (
                    <div key={member.id} className="flex items-center justify-between px-3 py-2.5 bg-stone-50 rounded-md">
                      <div className="flex items-center gap-3">
                        <div className="w-9 h-9 bg-primary-600 rounded-full flex items-center justify-center text-white text-sm font-medium">
                          {member.nickname?.[0] || member.username[0]}
                        </div>
                        <div>
                          <p className="text-sm font-medium text-stone-800">{member.nickname || member.username}</p>
                          <p className="text-xs text-stone-400">@{member.username}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className={cn('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-md', role.color)}>
                          <role.icon className="w-3 h-3" />
                          {role.label}
                        </span>
                        <button onClick={() => handleRemoveMember(member.id)} className="text-stone-300 hover:text-red-500 cursor-pointer p-1">
                          <X className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
