import { useState, useEffect, useCallback } from 'react';
import { X, Plus, Trash2, Shield } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { cn } from '../../lib/utils';
import type { FolderPermissionItem, FileNode, UserSearch } from '../../types';

interface FolderPermissionDialogProps {
  spaceId: string;
  node: FileNode;
  onClose: () => void;
}

const permissionLabels: Record<number, string> = { [-1]: '无权限', 0: '管理', 1: '编辑', 2: '查看' };

export default function FolderPermissionDialog({ spaceId, node, onClose }: FolderPermissionDialogProps) {
  const { showToast } = useToast();
  const [rules, setRules] = useState<FolderPermissionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [newSubjectType, setNewSubjectType] = useState<'member' | 'role'>('member');
  const [newKeyword, setNewKeyword] = useState('');
  const [searchResults, setSearchResults] = useState<UserSearch[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserSearch | null>(null);
  const [newRole, setNewRole] = useState(2);
  const [newPermission, setNewPermission] = useState(2);

  const fetchRules = useCallback(async () => {
    try { const res = await api.get<FolderPermissionItem[]>(`/team/${spaceId}/folder/${node.id}/permissions`); setRules(res || []); } catch { /* ignore */ } finally { setLoading(false); }
  }, [spaceId, node.id]);

  useEffect(() => { fetchRules(); }, [fetchRules]);

  const handleSearch = async (kw: string) => {
    setNewKeyword(kw); setSelectedUser(null);
    if (!kw.trim()) { setSearchResults([]); return; }
    try { const res = await api.get<UserSearch[]>(`/team/${spaceId}/users/search`, { params: { keyword: kw } }); setSearchResults(res || []); } catch { setSearchResults([]); }
  };

  const handleAddRule = () => {
    if (newSubjectType === 'member' && !selectedUser) { showToast('请先搜索并选择用户', 'warning'); return; }
    const subjectId = newSubjectType === 'member' ? selectedUser!.userId : newRole.toString();
    const subjectName = newSubjectType === 'member' ? (selectedUser!.nickname || selectedUser!.username) : permissionLabels[newRole];
    const newRule: FolderPermissionItem = {
      id: '', spaceId, folderNodeId: node.id, subjectType: newSubjectType,
      subjectId, subjectName, permission: newPermission, createdAt: '',
    };
    setRules(prev => [...prev, newRule]);
    setNewKeyword(''); setSelectedUser(null); setSearchResults([]);
  };

  const handleRemoveRule = (idx: number) => { setRules(prev => prev.filter((_, i) => i !== idx)); };

  const handleSave = async () => {
    try {
      const payload = rules.map(r => ({ subjectType: r.subjectType, subjectId: Number(r.subjectId), permission: r.permission }));
      await api.put(`/team/${spaceId}/folder/${node.id}/permissions`, { rules: payload });
      showToast('权限已保存', 'success'); onClose();
    } catch (e) { showToast('保存失败', 'error'); }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
          <h2 className="text-base font-semibold text-fg flex items-center gap-2"><Shield className="w-4 h-4" />文件夹权限：{node.name}</h2>
          <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" /></button>
        </div>
        <div className="p-5 space-y-4">
          {/* 权限规则列表 */}
          <div>
            <p className="text-xs font-medium text-muted mb-2">权限规则</p>
            {loading ? <p className="text-sm text-muted">加载中...</p> : rules.length === 0 ? <p className="text-sm text-muted">暂无规则，继承父文件夹权限</p> : (
              <div className="space-y-1.5">
                {rules.map((r, idx) => (
                  <div key={idx} className="flex items-center justify-between px-3 py-2 bg-surface-2 rounded-md">
                    <div className="flex items-center gap-2">
                      <span className={cn('text-xs px-1.5 py-0.5 rounded', r.subjectType === 'member' ? 'bg-primary-500/10 text-primary-600' : 'bg-amber-500/10 text-amber-600')}>{r.subjectType === 'member' ? '👤' : '👥'}</span>
                      <span className="text-sm text-fg">{r.subjectName}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <select value={r.permission} onChange={(e) => setRules(prev => prev.map((rr, i) => i === idx ? { ...rr, permission: parseInt(e.target.value) } : rr))} className="px-2 py-1 text-xs bg-surface-2 rounded border border-border outline-none cursor-pointer">
                        <option value={2}>查看</option><option value={1}>编辑</option><option value={0}>管理</option><option value={-1}>无权限</option>
                      </select>
                      <button onClick={() => handleRemoveRule(idx)} className="text-muted hover:text-red-500 cursor-pointer p-1" aria-label="删除规则"><Trash2 className="w-3.5 h-3.5" /></button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
          {/* 添加规则 */}
          <div className="pt-3 border-t border-border">
            <p className="text-xs font-medium text-muted mb-2">添加规则</p>
            <div className="flex flex-wrap gap-2">
              <select value={newSubjectType} onChange={(e) => { setNewSubjectType(e.target.value as 'member' | 'role'); setNewKeyword(''); setSelectedUser(null); }} className="px-2 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer">
                <option value="member">指定成员</option><option value="role">指定角色</option>
              </select>
              {newSubjectType === 'member' ? (
                <div className="flex-1 relative min-w-[120px]">
                  <input type="text" value={newKeyword} onChange={(e) => handleSearch(e.target.value)} placeholder="搜索用户..." className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400" />
                  {searchResults.length > 0 && !selectedUser && (
                    <div className="absolute z-10 top-full left-0 right-0 mt-1 bg-surface rounded-md border border-border shadow-lg max-h-40 overflow-auto">
                      {searchResults.map(u => <button key={u.userId} onClick={() => { setSelectedUser(u); setNewKeyword(u.nickname || u.username); setSearchResults([]); }} className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-surface-2 cursor-pointer text-left"><div className="w-6 h-6 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs">{u.nickname?.[0] || u.username[0]}</div><span>{u.nickname || u.username}</span><span className="text-xs text-muted">@{u.username}</span></button>)}
                    </div>
                  )}
                </div>
              ) : (
                <select value={newRole} onChange={(e) => setNewRole(parseInt(e.target.value))} className="px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer">
                  <option value={0}>管理员</option><option value={1}>编辑者</option><option value={2}>查看者</option>
                </select>
              )}
              <select value={newPermission} onChange={(e) => setNewPermission(parseInt(e.target.value))} className="px-2 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer">
                <option value={2}>查看</option><option value={1}>编辑</option><option value={0}>管理</option><option value={-1}>无权限</option>
              </select>
              <button onClick={handleAddRule} className="flex items-center gap-1 px-3 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 cursor-pointer"><Plus className="w-4 h-4" />添加</button>
            </div>
          </div>
          <div className="text-xs text-muted space-y-1 pt-2 border-t border-border">
            <p>· 查看：可浏览文件</p><p>· 编辑：可上传/修改/删除</p><p>· 管理：可管理权限</p><p>· 无权限：不可见</p>
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-border sticky bottom-0 bg-surface">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={handleSave} className="btn-primary">保存</button>
        </div>
      </div>
    </div>
  );
}