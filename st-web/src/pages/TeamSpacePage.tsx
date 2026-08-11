import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Users, X, UserPlus, Crown, Pencil, Eye, Upload, Settings, HardDrive, Link2, Copy, Trash2, Activity, LogOut, Send } from 'lucide-react';
import api from '../lib/api';
import FileBrowser from '../components/file/FileBrowser';
import { teamFileSource } from '../lib/fileSource';
import { useToast } from '../components/ui/Toast';
import { useUpload } from '../hooks/useUpload';
import { formatSize, cn } from '../lib/utils';
import { usePermission } from '../lib/permission';
import type { TeamSpace, TeamMember, TeamInvite, TeamActivity, FileNode, PageResult, UserSearch } from '../types';
import CommentPanel from '../components/team/CommentPanel';
import FolderPermissionDialog from '../components/team/FolderPermissionDialog';
import RoleManageDialog from '../components/team/RoleManageDialog';
import StatsPanel from '../components/team/StatsPanel';
import { Lock, Unlock, BarChart3, UserCog } from 'lucide-react';
import { MessageSquare, Shield } from 'lucide-react';

const EMOJI_ICONS = ['📁','📂','🚀','💡','🎨','⚙️','🏢','📊','🔥','⭐','🎯','🔧','📦','🎉','🔬','📚','💼','🖥️','🗂️','📌','🏷️','💬','🏗️','🌐'];

const roleConfig = [
  { label: '管理员', icon: Crown, color: 'text-amber-600 dark:text-amber-400 bg-amber-500/15' },
  { label: '编辑者', icon: Pencil, color: 'text-primary-600 bg-primary-500/10' },
  { label: '查看者', icon: Eye, color: 'text-muted bg-surface-2' },
];

const actionTextMap: Record<string, string> = {
  FILE_UPLOAD: '上传了文件', FILE_DELETE: '删除了', FILE_RENAME: '重命名了',
  FILE_MOVE: '移动了', FILE_COPY: '复制了', FOLDER_CREATE: '创建了文件夹',
  MEMBER_JOIN: '加入了空间', MEMBER_LEAVE: '退出了空间', MEMBER_INVITE: '邀请了',
  MEMBER_REMOVE: '移除了', MEMBER_ROLE_CHANGE: '修改了成员角色',
  SPACE_UPDATE: '修改了空间设置', SPACE_TRANSFER: '移交了空间所有权',
  INVITE_CREATE: '创建了邀请链接', INVITE_REVOKE: '撤销了邀请链接',
};

function timeAgo(iso: string | null): string {
  if (!iso) return '从未';
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day}天前`;
  return new Date(iso).toLocaleDateString('zh-CN');
}

function activeDotColor(iso: string | null): string {
  if (!iso) return 'bg-surface-2';
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 3600000) return 'bg-green-500';
  if (diff < 86400000) return 'bg-amber-500';
  if (diff < 604800000) return 'bg-gray-400';
  return 'bg-gray-300';
}

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
  const [activeTab, setActiveTab] = useState<'files' | 'activity'>('files');
  const [showMembers, setShowMembers] = useState(false);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [inviteKeyword, setInviteKeyword] = useState('');
  const [inviteUsers, setInviteUsers] = useState<UserSearch[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserSearch | null>(null);
  const [showUserDropdown, setShowUserDropdown] = useState(false);
  const userSearchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [inviteRole, setInviteRole] = useState(2);
  const [sortBy, setSortBy] = useState<'role' | 'active'>('role');
  const [invites, setInvites] = useState<TeamInvite[]>([]);
  const [newInviteRole, setNewInviteRole] = useState(2);
  const [newInviteExpiry, setNewInviteExpiry] = useState<'1d' | '7d' | 'permanent'>('7d');
  const [showSettings, setShowSettings] = useState(false);
  const [settingsForm, setSettingsForm] = useState({ spaceName: '', description: '', icon: '📁', storageQuota: '', quotaUnit: 'GB' });
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [activities, setActivities] = useState<TeamActivity[]>([]);
  const [activityFilter, setActivityFilter] = useState('ALL');
  const [activityPage, setActivityPage] = useState(1);
  const [activityHasMore, setActivityHasMore] = useState(true);
  const [showTransfer, setShowTransfer] = useState(false);
  const [commentNode, setCommentNode] = useState<FileNode | null>(null);
  const [permissionNode, setPermissionNode] = useState<FileNode | null>(null);
  const [showRoleManage, setShowRoleManage] = useState(false);
  const [showStats, setShowStats] = useState(false);
  const [showLockDialog, setShowLockDialog] = useState(false);
  const [lockHours, setLockHours] = useState(24);
  const [statsDays] = useState(7);
  const [transferTarget, setTransferTarget] = useState<string | null>(null);

  const fetchSpace = useCallback(async () => {
    try { const data: TeamSpace = await api.get(`/team/${spaceId}`); setSpace(data); } catch { /* ignore */ }
  }, [spaceId]);

  const fetchMembers = useCallback(async () => {
    try { const res = await api.get<PageResult<TeamMember>>(`/team/${spaceId}/members`, { params: { page: 1, size: 50, sortBy } }); setMembers(res?.records || []); } catch { /* ignore */ }
  }, [spaceId, sortBy]);

  const fetchInvites = useCallback(async () => {
    try { const res = await api.get<PageResult<TeamInvite>>(`/team/${spaceId}/invites`, { params: { page: 1, size: 50 } }); setInvites(res?.records || []); } catch { /* ignore */ }
  }, [spaceId]);

  const fetchActivities = useCallback(async (page: number, append: boolean) => {
    try { const res = await api.get<PageResult<TeamActivity>>(`/team/${spaceId}/activities`, { params: { filter: activityFilter, page, size: 20 } }); const records = res?.records || []; if (append) { setActivities(prev => [...prev, ...records]); } else { setActivities(records); } setActivityHasMore(records.length >= 20); } catch { /* ignore */ }
  }, [spaceId, activityFilter]);

  useEffect(() => { fetchSpace(); }, [fetchSpace]);
  useEffect(() => { if (showMembers) fetchMembers(); }, [sortBy]);
  useEffect(() => { if (activeTab === 'activity') { setActivityPage(1); fetchActivities(1, false); } }, [activeTab, fetchActivities]);

  const handleUserSearch = (keyword: string) => {
    setInviteKeyword(keyword);
    setSelectedUser(null);
    if (userSearchTimer.current) clearTimeout(userSearchTimer.current);
    if (!keyword.trim()) { setInviteUsers([]); setShowUserDropdown(false); return; }
    userSearchTimer.current = setTimeout(async () => {
      try { const res = await api.get<UserSearch[]>(`/team/${spaceId}/users/search`, { params: { keyword } }); setInviteUsers(res || []); setShowUserDropdown(true); } catch { setInviteUsers([]); }
    }, 300);
  };

  const handleSelectUser = (user: UserSearch) => {
    setSelectedUser(user);
    setInviteKeyword(user.nickname ? `${user.nickname} (@${user.username})` : `@${user.username}`);
    setShowUserDropdown(false);
  };

  const handleInvite = async () => {
    if (!selectedUser) { showToast('请先搜索并选择用户', 'warning'); return; }
    try { await api.post(`/team/${spaceId}/member`, { userId: selectedUser.userId, role: inviteRole }); showToast('邀请成功', 'success'); setInviteKeyword(''); setSelectedUser(null); setInviteUsers([]); fetchMembers(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '邀请失败', 'error'); }
  };

  const handleCreateInvite = async () => {
    let expireAt: string | null = null;
    if (newInviteExpiry === '1d') expireAt = new Date(Date.now() + 86400000).toISOString();
    else if (newInviteExpiry === '7d') expireAt = new Date(Date.now() + 7 * 86400000).toISOString();
    try { await api.post(`/team/${spaceId}/invite`, { role: newInviteRole, expireAt }); showToast('邀请链接已生成', 'success'); fetchInvites(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '生成失败', 'error'); }
  };

  const handleCopyInvite = (code: string) => {
    navigator.clipboard.writeText(`${window.location.origin}/team/invite/${code}`).then(() => showToast('链接已复制', 'success'));
  };

  const handleRevokeInvite = async (inviteId: string) => {
    if (!confirm('确定撤销该邀请链接？')) return;
    try { await api.delete(`/team/${spaceId}/invite/${inviteId}`); showToast('已撤销', 'success'); fetchInvites(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '操作失败', 'error'); }
  };

  const handleRemoveMember = async (memberId: string) => {
    if (!confirm('确定移除该成员？')) return;
    try { await api.delete(`/team/${spaceId}/member/${memberId}`); showToast('成员已移除', 'success'); fetchMembers(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '操作失败', 'error'); }
  };

  const handleLeaveSpace = async () => {
    if (!confirm('确定退出该空间？退出后将无法访问空间文件。')) return;
    try { await api.post(`/team/${spaceId}/leave`); showToast('已退出空间', 'success'); navigate('/team'); } catch (e: any) { showToast(e?.message || '退出失败', 'error'); }
  };

  const handleLock = async (nodeId: string, hours: number) => {
    try { await api.post(`/team/${spaceId}/files/${nodeId}/lock`, { hours }); showToast('已锁定', 'success'); } catch (e) { showToast((e instanceof Error ? e.message : '') || '锁定失败', 'error'); }
  };

  const handleUnlock = async (nodeId: string) => {
    try { await api.post(`/team/${spaceId}/files/${nodeId}/unlock`); showToast('已解锁', 'success'); } catch (e) { showToast((e instanceof Error ? e.message : '') || '解锁失败', 'error'); }
  };

  const handleTransfer = async () => {
    if (!transferTarget) { showToast('请选择移交目标', 'warning'); return; }
    try { await api.post(`/team/${spaceId}/transfer`, { targetMemberId: transferTarget }); showToast('所有权已移交', 'success'); setShowTransfer(false); setTransferTarget(null); fetchSpace(); fetchMembers(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '移交失败', 'error'); }
  };

  const openSettings = () => {
    if (space) {
      const quota = Number(space.storageQuota || 0);
      setSettingsForm({ spaceName: space.spaceName, description: space.description || '', icon: space.icon || '📁', storageQuota: quota >= 1024 * 1024 * 1024 ? (quota / (1024 * 1024 * 1024)).toFixed(1) : quota > 0 ? (quota / (1024 * 1024)).toFixed(0) : '', quotaUnit: quota >= 1024 * 1024 * 1024 ? 'GB' : quota > 0 ? 'MB' : 'GB' });
    }
    setShowSettings(true);
  };

  const handleSaveSettings = async () => {
    if (!settingsForm.spaceName.trim()) { showToast('请输入空间名称', 'warning'); return; }
    let quotaBytes: number | null = null;
    if (settingsForm.quotaUnit !== '0' && settingsForm.storageQuota) { const val = parseFloat(settingsForm.storageQuota); if (isNaN(val) || val <= 0) { showToast('请输入有效的配额数值', 'error'); return; } quotaBytes = settingsForm.quotaUnit === 'GB' ? Math.round(val * 1024 * 1024 * 1024) : Math.round(val * 1024 * 1024); }
    setSettingsLoading(true);
    try { await api.put(`/team/${spaceId}`, { spaceName: settingsForm.spaceName.trim(), description: settingsForm.description, icon: settingsForm.icon, storageQuota: quotaBytes }); showToast('设置已保存', 'success'); setShowSettings(false); fetchSpace(); } catch (e) { showToast((e instanceof Error ? e.message : '') || '保存失败', 'error'); } finally { setSettingsLoading(false); }
  };

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    if (selected.length > 0) { addFiles(selected, parentId || '0', undefined, spaceId); selected.forEach(f => { api.post(`/team/${spaceId}/activity`, null, { params: { action: 'FILE_UPLOAD', targetName: f.name } }).catch(() => {}); }); }
    e.target.value = '';
  };

  const navigateToFolder = (node: FileNode) => { if (!node.id || node.id === '0') { setBreadcrumbs([]); setParentId(null); return; } setBreadcrumbs((prev) => [...prev, { id: node.id, name: node.name }]); setParentId(node.id); };
  const navigateToCrumb = (idx: number) => { if (idx === -1) { setBreadcrumbs([]); setParentId(null); } else { setBreadcrumbs(breadcrumbs.slice(0, idx + 1)); setParentId(breadcrumbs[idx].id); } };
  const handleBack = () => { if (breadcrumbs.length > 0) navigateToCrumb(breadcrumbs.length - 2); else navigate('/team'); };
  const loadMoreActivities = () => { const next = activityPage + 1; setActivityPage(next); fetchActivities(next, true); };
  const usedPercent = space ? (Number(space.storageQuota) > 0 ? Math.min(100, (Number(space.storageUsed) / Number(space.storageQuota)) * 100) : 0) : 0;

  return (
    <div className="flex flex-col h-full">
      <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleFileInputChange} />
      <div className="flex items-center justify-between px-6 py-3 border-b border-border bg-surface">
        <div className="flex items-center gap-3 min-w-0">
          <button onClick={() => navigate('/team')} className="text-muted hover:text-fg cursor-pointer flex-shrink-0" aria-label="返回"><ArrowLeft className="w-5 h-5" aria-hidden /></button>
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center flex-shrink-0 text-sm">{space?.icon || <Users className="w-4 h-4 text-white" />}</div>
          <div className="min-w-0"><h1 className="text-lg font-semibold text-fg truncate">{space?.spaceName || '加载中…'}</h1>{space?.description && <p className="text-xs text-muted truncate">{space.description}</p>}</div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <button onClick={() => { setShowMembers(true); fetchMembers(); fetchInvites(); }} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer"><Users className="w-4 h-4" /><span className="hidden sm:inline">成员</span>{space?.memberCount !== undefined && <span className="text-xs text-muted">{space.memberCount}</span>}</button>
          <button onClick={openSettings} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="空间设置"><Settings className="w-4 h-4" /><span className="hidden sm:inline">设置</span></button>
          <button onClick={() => setCommentNode({ id: parentId || '0', name: space?.spaceName || '空间' } as FileNode)} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="评论"><MessageSquare className="w-4 h-4" /></button>
          {space?.ownerId && <button onClick={() => setPermissionNode({ id: parentId || '0', name: '空间根目录' } as FileNode)} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="文件夹权限"><Shield className="w-4 h-4" /></button>}
          <button onClick={() => setShowRoleManage(true)} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="角色管理"><UserCog className="w-4 h-4" /></button>
          <button onClick={() => setShowStats(true)} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="空间统计"><BarChart3 className="w-4 h-4" /></button>
          <button onClick={() => setShowLockDialog(true)} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="锁定当前文件夹"><Lock className="w-4 h-4" /></button>
          <button onClick={() => handleUnlock(parentId || '0')} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted bg-surface-2 rounded-md hover:bg-surface-2 transition-colors cursor-pointer" title="解锁当前文件夹"><Unlock className="w-4 h-4" /></button>
          {has('file:upload') && <button onClick={() => fileInputRef.current?.click()} className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer"><Upload className="w-4 h-4" /><span className="hidden sm:inline">上传</span></button>}
        </div>
      </div>
      {space && <div className="flex items-center gap-2 px-6 py-1.5 border-b border-border bg-surface-2/50 text-xs text-muted cursor-pointer hover:bg-surface-2/50 transition-colors" onClick={openSettings} title="点击管理存储配额"><HardDrive className="w-3.5 h-3.5" /><span className="flex-1">存储: {formatSize(Number(space.storageUsed))} / {Number(space.storageQuota) > 0 ? formatSize(Number(space.storageQuota)) : '不限制'}</span><div className="w-32 h-1.5 bg-surface-2 rounded-full overflow-hidden"><div className={cn('h-full rounded-full transition-[width,background-color]', usedPercent > 90 ? 'bg-red-500' : usedPercent > 70 ? 'bg-amber-500' : 'bg-primary-500')} style={{ width: `${usedPercent}%` }} /></div><span className="text-muted">{usedPercent > 0 ? `${Math.round(usedPercent)}%` : ''}</span></div>}
      <div className="flex items-center gap-1 px-6 py-2 border-b border-border bg-surface">
        <button onClick={() => setActiveTab('files')} className={cn('flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors cursor-pointer', activeTab === 'files' ? 'text-primary-600 bg-primary-500/10' : 'text-muted hover:text-fg')}><HardDrive className="w-4 h-4" /> 文件</button>
        <button onClick={() => setActiveTab('activity')} className={cn('flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors cursor-pointer', activeTab === 'activity' ? 'text-primary-600 bg-primary-500/10' : 'text-muted hover:text-fg')}><Activity className="w-4 h-4" /> 动态</button>
      </div>
      <div className={cn('flex-1 min-h-0 overflow-hidden', activeTab !== 'files' && 'hidden')}>
        <FileBrowser key={parentId || "root"} source={source} parentId={parentId} onNavigateFolder={navigateToFolder} onBack={handleBack} uploadSpaceId={spaceId} enableShare={false} enableVersions={false} />
      </div>
      {activeTab === 'activity' && (
        <div className="flex-1 overflow-auto p-6">
          <div className="max-w-3xl mx-auto">
            <div className="flex items-center gap-2 mb-4"><select value={activityFilter} onChange={(e) => { setActivityFilter(e.target.value); setActivityPage(1); }} className="px-3 py-1.5 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer"><option value="ALL">全部</option><option value="FILE">文件操作</option><option value="MEMBER">成员操作</option><option value="SPACE">空间操作</option></select></div>
            <div className="space-y-3">
              {activities.length === 0 ? (<div className="text-center py-12 text-muted"><Activity className="w-10 h-10 mx-auto mb-2 opacity-30" /><p className="text-sm">暂无动态</p></div>) : (
                <>{activities.map(act => (<div key={act.id} className="flex items-start gap-3 px-4 py-3 bg-surface rounded-lg border border-border"><div className="w-9 h-9 bg-primary-600 rounded-full flex items-center justify-center text-white text-sm font-medium flex-shrink-0">{act.nickname?.[0] || act.username?.[0] || '?'}</div><div className="min-w-0 flex-1"><p className="text-sm text-fg"><span className="font-medium">{act.nickname || act.username}</span> <span className="text-muted">{actionTextMap[act.action] || act.action}</span>{act.targetName && <span className="font-medium text-fg"> {act.targetName}</span>}</p><p className="text-xs text-muted mt-0.5">{timeAgo(act.createdAt)}</p></div></div>))}
                  {activityHasMore && <button onClick={loadMoreActivities} className="w-full py-2 text-sm text-primary-600 hover:text-primary-700 cursor-pointer">加载更多...</button>}
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {showSettings && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={() => setShowSettings(false)}>
          <div className="w-full max-w-md bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10"><h2 className="text-base font-semibold text-fg">空间设置</h2><button onClick={() => setShowSettings(false)} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" aria-hidden /></button></div>
            <div className="p-5 space-y-5">
              <div><label className="text-xs font-medium text-muted mb-1.5 block">空间名称 *</label><input type="text" value={settingsForm.spaceName} onChange={(e) => setSettingsForm({ ...settingsForm, spaceName: e.target.value })} placeholder="如：产品研发组" className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400 focus:bg-surface transition-colors" /></div>
              <div><label className="text-xs font-medium text-muted mb-1.5 block">空间描述</label><textarea value={settingsForm.description} onChange={(e) => setSettingsForm({ ...settingsForm, description: e.target.value })} placeholder="简要描述空间用途" rows={2} className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400 focus:bg-surface transition-colors resize-none" /></div>
              <div><label className="text-xs font-medium text-muted mb-1.5 block">空间图标</label><div className="grid grid-cols-8 gap-1.5">{EMOJI_ICONS.map(emoji => (<button key={emoji} onClick={() => setSettingsForm({ ...settingsForm, icon: emoji })} className={cn('w-9 h-9 flex items-center justify-center rounded-md text-lg cursor-pointer transition-colors', settingsForm.icon === emoji ? 'bg-primary-500/15 ring-2 ring-primary-500' : 'bg-surface-2 hover:bg-surface-2')} aria-label={`选择图标 ${emoji}`}>{emoji}</button>))}</div></div>
              <div><label className="text-xs font-medium text-muted mb-1.5 block">存储配额</label><div className="flex gap-2"><input type="number" value={settingsForm.storageQuota} onChange={(e) => setSettingsForm({ ...settingsForm, storageQuota: e.target.value })} placeholder="不限制则留空" className="flex-1 px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400 focus:bg-surface transition-colors" /><select value={settingsForm.quotaUnit} onChange={(e) => setSettingsForm({ ...settingsForm, quotaUnit: e.target.value })} className="px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer"><option value="MB">MB</option><option value="GB">GB</option></select></div></div>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3 border-t border-border sticky bottom-0 bg-surface"><button onClick={() => setShowSettings(false)} className="btn-secondary">取消</button><button onClick={handleSaveSettings} disabled={settingsLoading} className="btn-primary">{settingsLoading ? '保存中…' : '保存'}</button></div>
          </div>
        </div>
      )}

      {showMembers && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={() => setShowMembers(false)}>
          <div className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] overflow-y-auto animate-scale-in" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10"><h2 className="text-base font-semibold text-fg">成员管理</h2><button onClick={() => setShowMembers(false)} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" aria-hidden /></button></div>
            <div className="p-5 space-y-4">
              <div><p className="text-xs font-medium text-muted mb-1.5">搜索用户邀请</p><div className="flex gap-2"><div className="flex-1 relative"><input type="text" value={inviteKeyword} onChange={(e) => handleUserSearch(e.target.value)} onFocus={() => inviteUsers.length > 0 && setShowUserDropdown(true)} onBlur={() => setTimeout(() => setShowUserDropdown(false), 200)} placeholder="输入用户名或昵称搜索" className={cn('w-full px-3 py-2 text-sm bg-surface-2 rounded-md border outline-none focus:bg-surface transition-colors', selectedUser ? 'border-primary-400' : 'border-border focus:border-primary-400')} />{showUserDropdown && inviteUsers.length > 0 && (<div className="absolute z-20 top-full left-0 right-0 mt-1 bg-surface rounded-md border border-border shadow-lg max-h-60 overflow-auto">{inviteUsers.map(u => (<button key={u.userId} onClick={() => handleSelectUser(u)} className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-surface-2 transition-colors cursor-pointer text-left"><div className="w-7 h-7 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs font-medium flex-shrink-0">{u.nickname?.[0] || u.username[0]}</div><div className="min-w-0"><p className="text-sm text-fg truncate">{u.nickname || u.username}</p><p className="text-xs text-muted truncate">@{u.username}</p></div></button>))}</div>)}{showUserDropdown && inviteUsers.length === 0 && inviteKeyword.trim() && (<div className="absolute z-20 top-full left-0 right-0 mt-1 bg-surface rounded-md border border-border shadow-lg px-3 py-2 text-sm text-muted">未找到匹配用户</div>)}</div><select value={inviteRole} onChange={(e) => setInviteRole(parseInt(e.target.value))} className="px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer"><option value={0}>管理员</option><option value={1}>编辑者</option><option value={2}>查看者</option></select><button onClick={handleInvite} className="flex items-center gap-1 px-4 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer whitespace-nowrap"><UserPlus className="w-4 h-4" /> 邀请</button></div></div>
              <div className="pt-2 border-t border-border"><p className="text-xs font-medium text-muted mb-1.5">邀请链接</p><div className="flex gap-2 mb-2"><select value={newInviteRole} onChange={(e) => setNewInviteRole(parseInt(e.target.value))} className="px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer"><option value={0}>管理员</option><option value={1}>编辑者</option><option value={2}>查看者</option></select><select value={newInviteExpiry} onChange={(e) => setNewInviteExpiry(e.target.value as '1d' | '7d' | 'permanent')} className="px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer"><option value="1d">24小时</option><option value="7d">7天</option><option value="permanent">永久</option></select><button onClick={handleCreateInvite} className="flex items-center gap-1 px-4 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer whitespace-nowrap"><Link2 className="w-4 h-4" /> 生成</button></div>
                {invites.length > 0 && (<div className="space-y-1.5 max-h-40 overflow-auto">{invites.map(inv => (<div key={inv.id} className={cn('flex items-center justify-between px-3 py-2 bg-surface-2 rounded-md', inv.status === 0 && 'opacity-50')}><div className="flex items-center gap-2 min-w-0"><Link2 className="w-3.5 h-3.5 text-muted flex-shrink-0" /><div className="min-w-0"><p className="text-xs text-fg truncate">/team/invite/{inv.inviteCode.slice(0, 12)}...</p><p className="text-xs text-muted">{roleConfig[inv.role]?.label} · {inv.expireAt ? `至 ${new Date(inv.expireAt).toLocaleDateString('zh-CN')}` : '永久'}{inv.status === 0 ? ' · 已撤销' : ''}</p></div></div><div className="flex items-center gap-1 flex-shrink-0">{inv.status === 1 && (<><button onClick={() => handleCopyInvite(inv.inviteCode)} className="p-1 text-muted hover:text-primary-600 cursor-pointer" aria-label="复制链接"><Copy className="w-3.5 h-3.5" /></button><button onClick={() => handleRevokeInvite(inv.id)} className="p-1 text-muted hover:text-red-500 cursor-pointer" aria-label="撤销"><Trash2 className="w-3.5 h-3.5" /></button></>)}</div></div>))}</div>)}
              </div>
              <div className="pt-2 border-t border-border">
                <div className="flex items-center justify-between mb-2"><p className="text-xs font-medium text-muted">成员列表</p><button onClick={() => { setSortBy(sortBy === 'role' ? 'active' : 'role'); }} className="text-xs text-primary-600 hover:text-primary-700 cursor-pointer">{sortBy === 'role' ? '按活跃排序' : '按角色排序'}</button></div>
                <div className="space-y-2 max-h-60 overflow-auto">{members.map((member) => { const role = roleConfig[member.role] || roleConfig[2]; const memberIsOwner = space?.ownerId === member.userId; return (<div key={member.id} className="flex items-center justify-between px-3 py-2.5 bg-surface-2 rounded-md"><div className="flex items-center gap-3 min-w-0"><div className="w-9 h-9 bg-primary-600 rounded-full flex items-center justify-center text-white text-sm font-medium flex-shrink-0">{member.nickname?.[0] || member.username[0]}</div><div className="min-w-0"><div className="flex items-center gap-1.5"><span className={cn('w-1.5 h-1.5 rounded-full flex-shrink-0', activeDotColor(member.lastActiveAt))} /><p className="text-sm font-medium text-fg truncate">{member.nickname || member.username}</p>{memberIsOwner && <Crown className="w-3 h-3 text-amber-500 flex-shrink-0" />}</div><p className="text-xs text-muted">@{member.username} · {timeAgo(member.lastActiveAt)}</p></div></div><div className="flex items-center gap-2 flex-shrink-0"><span className={cn('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-md', role.color)}><role.icon className="w-3 h-3" />{role.label}</span>{!memberIsOwner && <button onClick={() => handleRemoveMember(member.id)} className="text-muted hover:text-red-500 cursor-pointer p-1" aria-label="移除成员"><X className="w-4 h-4" aria-hidden /></button>}</div></div>); })}</div>
              </div>
              <div className="pt-2 border-t border-border flex items-center justify-between"><button onClick={handleLeaveSpace} className="flex items-center gap-1.5 text-sm text-red-500 hover:text-red-600 cursor-pointer"><LogOut className="w-4 h-4" /> 退出空间</button>{space && members.some(m => m.userId === space.ownerId && m.role === 0) && <button onClick={() => setShowTransfer(true)} className="flex items-center gap-1.5 text-sm text-primary-600 hover:text-primary-700 cursor-pointer"><Send className="w-4 h-4" /> 移交所有权</button>}</div>
            </div>
          </div>
        </div>
      )}

      {showTransfer && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={() => setShowTransfer(false)}>
          <div className="w-full max-w-sm bg-surface rounded-xl shadow-lg border border-border overflow-hidden" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-border"><h2 className="text-base font-semibold text-fg">移交所有权</h2><button onClick={() => setShowTransfer(false)} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" aria-hidden /></button></div>
            <div className="p-5 space-y-3">
              <p className="text-sm text-muted">选择新的空间拥有者（仅管理员可移交）：</p>
              {members.filter(m => m.role === 0 && m.userId !== space?.ownerId).map(m => (
                <label key={m.id} className={cn('flex items-center gap-3 px-3 py-2.5 rounded-md cursor-pointer transition-colors', transferTarget === m.id ? 'bg-primary-500/10 ring-1 ring-primary-400' : 'bg-surface-2 hover:bg-surface-2')}>
                  <input type="radio" name="transfer" checked={transferTarget === m.id} onChange={() => setTransferTarget(m.id)} className="cursor-pointer" />
                  <div className="w-8 h-8 bg-primary-600 rounded-full flex items-center justify-center text-white text-sm font-medium">{m.nickname?.[0] || m.username[0]}</div>
                  <div><p className="text-sm font-medium text-fg">{m.nickname || m.username}</p><p className="text-xs text-muted">@{m.username}</p></div>
                </label>
              ))}
              <p className="text-xs text-muted">移交后您将降为普通管理员。</p>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3 border-t border-border"><button onClick={() => setShowTransfer(false)} className="btn-secondary">取消</button><button onClick={handleTransfer} className="btn-primary">确认移交</button></div>
          </div>
        </div>
      )}

      {showLockDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={() => setShowLockDialog(false)}>
          <div className="w-full max-w-sm bg-surface rounded-xl shadow-lg border border-border overflow-hidden" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b border-border"><h2 className="text-base font-semibold text-fg">锁定当前文件夹</h2><button onClick={() => setShowLockDialog(false)} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" aria-hidden /></button></div>
            <div className="p-5 space-y-3">
              <p className="text-sm text-muted">锁定后其他成员将无法编辑该文件夹下的内容。选择锁定时长：</p>
              <div className="space-y-2">
                {[{ label: '1 小时', value: 1 }, { label: '6 小时', value: 6 }, { label: '24 小时', value: 24 }, { label: '永久', value: 0 }].map(opt => (
                  <label key={opt.value} className={cn('flex items-center gap-3 px-3 py-2.5 rounded-md cursor-pointer transition-colors', lockHours === opt.value ? 'bg-primary-500/10 ring-1 ring-primary-400' : 'bg-surface-2 hover:bg-surface-2')}>
                    <input type="radio" name="lockHours" checked={lockHours === opt.value} onChange={() => setLockHours(opt.value)} className="cursor-pointer" />
                    <span className="text-sm text-fg">{opt.label}</span>
                  </label>
                ))}
              </div>
            </div>
            <div className="flex justify-end gap-2 px-5 py-3 border-t border-border"><button onClick={() => setShowLockDialog(false)} className="btn-secondary">取消</button><button onClick={() => { handleLock(parentId || '0', lockHours); setShowLockDialog(false); }} className="btn-primary">确认锁定</button></div>
          </div>
        </div>
      )}
    </div>
  );
}
