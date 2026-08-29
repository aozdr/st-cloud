import { useState, useEffect, useCallback, useRef, type Dispatch, type SetStateAction } from 'react';
import { X, Plus, Trash2, Shield, Pencil, Check } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { cn } from '../../lib/utils';
import type { FolderPermissionItem, FileNode, UserSearch, TeamRoleInfo } from '../../types';
import { PERMISSION_KEYS, legacyToPermissions } from '../../lib/permissions';

interface FolderPermissionDialogProps {
  spaceId: string;
  node: FileNode;
  onClose: () => void;
  /** dialog=居中弹窗（默认，顶部权限按钮）；panel=页面级权限 tab（占满容器） */
  variant?: 'dialog' | 'panel';
}

type SubjectType = 'all' | 'member' | 'role';

/** 内置角色（subjectId 与后端一致：0=管理员 1=编辑者 2=查看者） */
const PRESET_ROLES: { id: string; name: string }[] = [
  { id: '0', name: '管理员' },
  { id: '1', name: '编辑者' },
  { id: '2', name: '查看者' },
];

/** 防御式解析权限点：后端返回字符串 JSON，前端也可能直接拿到对象 */
function parsePermissions(raw: unknown): Record<string, boolean> {
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const obj = JSON.parse(raw);
      return obj && typeof obj === 'object' ? (obj as Record<string, boolean>) : {};
    } catch {
      return {};
    }
  }
  if (raw && typeof raw === 'object') return raw as Record<string, boolean>;
  return {};
}

/** 规则的有效权限集：优先 permissions，缺失时回退旧单值 */
function rulePermissions(r: FolderPermissionItem): Record<string, boolean> {
  const parsed = parsePermissions(r.permissions);
  return Object.keys(parsed).length > 0 ? parsed : legacyToPermissions(r.permission);
}

/** 紧凑权限点开关：勾选态=主色填充+对勾，未勾选=次级面；窄栏下比 checkbox 卡片更省空间、更易扫读 */
function PermissionToggle({ label, checked, onToggle }: { label: string; checked: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      onClick={onToggle}
      className={cn(
        'flex items-center gap-1.5 px-2 py-1.5 rounded-md text-xs cursor-pointer select-none transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        checked
          ? 'bg-primary-500/15 text-primary-600 border border-primary-500/30'
          : 'bg-surface-2 text-fg border border-transparent hover:bg-surface-2/70',
      )}
    >
      <span
        className={cn(
          'w-3.5 h-3.5 rounded-[4px] border flex items-center justify-center flex-shrink-0 transition-colors',
          checked ? 'bg-primary-600 border-primary-600' : 'border-muted bg-surface',
        )}
      >
        {checked && <Check className="w-2.5 h-2.5 text-white" aria-hidden />}
      </span>
      <span className="truncate">{label}</span>
    </button>
  );
}

export default function FolderPermissionDialog({ spaceId, node, onClose, variant = 'dialog' }: FolderPermissionDialogProps) {
  const { showToast } = useToast();
  const isPanel = variant === 'panel';
  const [rules, setRules] = useState<FolderPermissionItem[]>([]);
  const [roles, setRoles] = useState<TeamRoleInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [newSubjectType, setNewSubjectType] = useState<SubjectType>('all');
  const [newKeyword, setNewKeyword] = useState('');
  const [searchResults, setSearchResults] = useState<UserSearch[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserSearch | null>(null);
  const [newRoleId, setNewRoleId] = useState('2');
  const [newPerms, setNewPerms] = useState<Record<string, boolean>>({ view: true });
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editPerms, setEditPerms] = useState<Record<string, boolean>>({});

  const fetchRules = useCallback(async () => {
    try {
      const res = await api.get<FolderPermissionItem[]>(`/team/${spaceId}/folder/${node.id}/permissions`);
      setRules(res || []);
    } catch { /* ignore */ } finally { setLoading(false); }
  }, [spaceId, node.id]);

  const fetchRoles = useCallback(async () => {
    try { const res = await api.get<TeamRoleInfo[]>(`/team/${spaceId}/roles`); setRoles(res || []); } catch { /* ignore */ }
  }, [spaceId]);

  useEffect(() => { fetchRules(); fetchRoles(); }, [fetchRules, fetchRoles]);

  /** 自定义角色（排除预设与停用） */
  const customRoles = roles.filter(r => !r.isPreset && r.status !== 0);

  const roleNameById = (id: string) => {
    const preset = PRESET_ROLES.find(r => r.id === id);
    if (preset) return preset.name;
    return customRoles.find(r => String(r.id) === id)?.name || '未知角色';
  };

  /** 生成勾选切换处理器：upload/download 隐含 view；取消 view 联动取消 upload/download */
  const makeToggle = (setter: Dispatch<SetStateAction<Record<string, boolean>>>) => (key: string) => {
    setter(prev => {
      const next = { ...prev, [key]: !prev[key] };
      if ((key === 'upload' || key === 'download') && next[key]) next.view = true;
      if (key === 'view' && !next[key]) { next.upload = false; next.download = false; }
      return next;
    });
  };
  const toggleNewPerm = makeToggle(setNewPerms);
  const toggleEditPerm = makeToggle(setEditPerms);

  // 用户搜索：300ms debounce，避免每键一次请求；失败给出提示（后端要求管理员权限，非管理员会被拒）
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handleSearch = (kw: string) => {
    setNewKeyword(kw); setSelectedUser(null);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    if (!kw.trim()) { setSearchResults([]); return; }
    searchTimer.current = setTimeout(async () => {
      try {
        const res = await api.get<UserSearch[]>(`/team/${spaceId}/users/search`, { params: { keyword: kw.trim() } });
        setSearchResults(res || []);
        if (!res || res.length === 0) showToast('未找到匹配用户（可能已是成员或非管理员）', 'warning');
      } catch { setSearchResults([]); showToast('用户搜索失败（可能无搜索权限）', 'error'); }
    }, 300);
  };
  useEffect(() => () => { if (searchTimer.current) clearTimeout(searchTimer.current); }, []);

  const handleSubjectTypeChange = (t: SubjectType) => {
    setNewSubjectType(t); setNewKeyword(''); setSelectedUser(null); setSearchResults([]);
  };

  const handleAddRule = () => {
    if (newSubjectType === 'member' && !selectedUser) { showToast('请先搜索并选择用户', 'warning'); return; }
    let subjectId = '0';
    let subjectName = '全体成员';
    if (newSubjectType === 'member' && selectedUser) {
      subjectId = selectedUser.userId;
      subjectName = selectedUser.nickname || selectedUser.username;
    } else if (newSubjectType === 'role') {
      subjectId = newRoleId;
      subjectName = roleNameById(newRoleId);
    }
    const dupIdx = rules.findIndex(r => r.subjectType === newSubjectType && String(r.subjectId) === String(subjectId));
    if (dupIdx >= 0) {
      showToast('该主体已存在规则，将更新其权限', 'warning');
      setRules(prev => prev.map((r, i) => i === dupIdx ? { ...r, permissions: { ...newPerms } } : r));
    } else {
      const newRule: FolderPermissionItem = {
        id: '', spaceId, folderNodeId: node.id, subjectType: newSubjectType,
        subjectId, subjectName, permission: 2, permissions: { ...newPerms }, createdAt: '',
      };
      setRules(prev => [...prev, newRule]);
    }
    setNewKeyword(''); setSelectedUser(null); setSearchResults([]);
    setNewPerms({ view: true });
  };

  const handleRemoveRule = (idx: number) => {
    setRules(prev => prev.filter((_, i) => i !== idx));
    if (editingIdx !== null) {
      if (editingIdx === idx) setEditingIdx(null);
      else if (editingIdx > idx) setEditingIdx(editingIdx - 1);
    }
  };

  const openEditor = (idx: number) => {
    setEditingIdx(idx);
    setEditPerms(rulePermissions(rules[idx]));
  };

  const applyEdit = () => {
    if (editingIdx === null) return;
    setRules(prev => prev.map((r, i) => i === editingIdx ? { ...r, permissions: { ...editPerms } } : r));
    setEditingIdx(null);
  };

  const handleSave = async () => {
    try {
      const payload = rules.map(r => ({
        subjectType: r.subjectType,
        subjectId: r.subjectType === 'all' ? '0' : String(r.subjectId),
        permissions: JSON.stringify(rulePermissions(r)),
      }));
      await api.put(`/team/${spaceId}/folder/${node.id}/permissions`, { rules: payload });
      showToast('权限已保存', 'success');
      // 面板模式：保存后停留在权限 tab 并回显已保存规则；弹窗模式：保存后关闭
      if (variant === 'dialog') onClose();
    } catch { showToast('保存失败', 'error'); }
  };

  const subjectBadge = (type: SubjectType) => {
    if (type === 'all') return { icon: '🌐', cls: 'bg-emerald-500/10 text-emerald-600' };
    if (type === 'role') return { icon: '👥', cls: 'bg-amber-500/10 text-amber-600' };
    return { icon: '👤', cls: 'bg-primary-500/10 text-primary-600' };
  };

  /** 规则展示名称：自定义角色回退到角色名（后端 VO 仅映射内置角色名） */
  const ruleDisplayName = (r: FolderPermissionItem) => {
    if (r.subjectType === 'role' && !PRESET_ROLES.some(p => p.id === String(r.subjectId))) {
      return roleNameById(String(r.subjectId));
    }
    return r.subjectName || '未知';
  };

  const permissionChips = (r: FolderPermissionItem): string[] => {
    const perms = rulePermissions(r);
    const labels = PERMISSION_KEYS.filter(p => perms[p.key]).map(p => p.label);
    return labels.length > 0 ? labels : ['无权限'];
  };

  // 内容主体：弹窗与面板（权限 tab）共用同一套规则列表、权限点勾选与保存逻辑
  const content = (
    <>
      {!isPanel && (
        <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
          <h2 className="text-base font-semibold text-fg flex items-center gap-2"><Shield className="w-4 h-4" />权限：{node.name}</h2>
          <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" /></button>
        </div>
      )}
        <div className={cn('space-y-4', isPanel ? 'p-4 space-y-3' : 'p-5 space-y-4')}>
          {/* 权限规则列表 */}
          <div>
            <p className="text-xs font-medium text-muted mb-2">权限规则</p>
            {loading ? <p className="text-sm text-muted">加载中...</p> : rules.length === 0 ? <p className="text-sm text-muted">暂无规则，继承父文件夹权限</p> : (
              <div className="space-y-1.5">
                {rules.map((r, idx) => {
                  const badge = subjectBadge(r.subjectType);
                  return (
                    <div key={idx} className={cn('bg-surface-2 rounded-md', isPanel ? 'px-2.5 py-2' : 'px-3 py-2')}>
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className={cn('text-xs px-1.5 py-0.5 rounded shrink-0', badge.cls)}>{badge.icon}</span>
                          <span className="text-sm text-fg truncate">{ruleDisplayName(r)}</span>
                        </div>
                        <div className="flex items-center gap-1 shrink-0">
                          <button onClick={() => openEditor(idx)} className="text-muted hover:text-primary-600 cursor-pointer p-1" aria-label="编辑权限"><Pencil className="w-3.5 h-3.5" /></button>
                          <button onClick={() => handleRemoveRule(idx)} className="text-muted hover:text-red-500 cursor-pointer p-1" aria-label="删除规则"><Trash2 className="w-3.5 h-3.5" /></button>
                        </div>
                      </div>
                      <div className="mt-1.5 flex flex-wrap gap-1">
                        {permissionChips(r).map(label => (
                          <span key={label} className={cn('text-[11px] px-1.5 py-0.5 rounded', label === '无权限' ? 'bg-surface text-muted' : 'bg-primary-500/10 text-primary-600')}>{label}</span>
                        ))}
                      </div>
                      {editingIdx === idx && (
                        <div className="mt-2 pt-2 border-t border-border">
                          <div className={cn('grid gap-1.5', isPanel ? 'grid-cols-2' : 'grid-cols-2 sm:grid-cols-3')}>
                            {PERMISSION_KEYS.map(p => (
                              <PermissionToggle key={p.key} label={p.label} checked={editPerms[p.key] || false} onToggle={() => toggleEditPerm(p.key)} />
                            ))}
                          </div>
                          <div className="flex justify-end gap-2 mt-2">
                            <button onClick={() => setEditingIdx(null)} className="btn-secondary">取消</button>
                            <button onClick={applyEdit} className="btn-primary">确定</button>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
          {/* 添加规则 */}
          <div className="pt-3 border-t border-border">
            <p className="text-xs font-medium text-muted mb-2">添加规则</p>
            <div className={cn('gap-2', isPanel ? 'flex flex-col' : 'flex flex-wrap')}>
              <select value={newSubjectType} onChange={(e) => handleSubjectTypeChange(e.target.value as SubjectType)} aria-label="规则主体类型" className={cn('px-2 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer', isPanel ? 'w-full' : '')}>
                <option value="all">全体成员</option>
                <option value="member">指定成员</option>
                <option value="role">指定角色</option>
              </select>
              {newSubjectType === 'member' ? (
                <div className={cn('relative', isPanel ? 'w-full' : 'flex-1 min-w-[150px]')}>
                  <input type="text" value={newKeyword} onChange={(e) => handleSearch(e.target.value)} placeholder="搜索用户..." aria-label="搜索用户" className="w-full px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400" />
                  {searchResults.length > 0 && !selectedUser && (
                    <div className="absolute z-10 top-full left-0 right-0 mt-1 bg-surface rounded-md border border-border shadow-lg max-h-40 overflow-auto">
                      {searchResults.map(u => <button key={u.userId} onClick={() => { setSelectedUser(u); setNewKeyword(u.nickname || u.username); setSearchResults([]); }} className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-surface-2 cursor-pointer text-left"><div className="w-6 h-6 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs">{u.nickname?.[0] || u.username[0]}</div><span>{u.nickname || u.username}</span><span className="text-xs text-muted">@{u.username}</span></button>)}
                    </div>
                  )}
                </div>
              ) : newSubjectType === 'role' ? (
                <select value={newRoleId} onChange={(e) => setNewRoleId(e.target.value)} aria-label="选择角色" className={cn('px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none cursor-pointer', isPanel ? 'w-full' : '')}>
                  {PRESET_ROLES.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                  {customRoles.map(r => <option key={r.id} value={String(r.id)}>{r.name}（自定义）</option>)}
                </select>
              ) : (
                <span className="text-xs text-muted self-center">全体成员（管理员除外）</span>
              )}
            </div>
            <div className="mt-2">
              <p className="text-xs font-medium text-muted mb-1.5">权限点</p>
              <div className={cn('grid gap-1.5', isPanel ? 'grid-cols-2' : 'grid-cols-2 sm:grid-cols-3')}>
                {PERMISSION_KEYS.map(p => (
                  <PermissionToggle key={p.key} label={p.label} checked={newPerms[p.key] || false} onToggle={() => toggleNewPerm(p.key)} />
                ))}
              </div>
              <p className="text-[11px] text-muted mt-1.5">勾选「上传/下载」会自动补上「查看」；取消「查看」会同步取消「上传/下载」（与后端隐含规则一致）。</p>
            </div>
            <div className="mt-2 flex justify-end">
              <button onClick={handleAddRule} className="flex items-center gap-1 px-3 py-2 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 cursor-pointer"><Plus className="w-4 h-4" />添加</button>
            </div>
          </div>
        </div>
      {/* 面板模式按钮左对齐：避开右下角悬浮上传按钮的遮挡；弹窗模式保持右对齐 */}
      <div className={cn('flex gap-2 px-5 py-3 border-t border-border sticky bottom-0 bg-surface', isPanel ? 'justify-start' : 'justify-end')}>
        {variant === 'dialog' && <button onClick={onClose} className="btn-secondary">取消</button>}
        <button onClick={handleSave} className="btn-primary">保存</button>
      </div>
    </>
  );

  // 面板模式：页面级权限 tab，占满容器并在内部滚动；弹窗模式：居中遮罩弹窗
  if (variant === 'panel') {
    return (
      <div className="h-full w-full flex flex-col bg-surface">
        <div className="flex-1 min-h-0 overflow-y-auto">{content}</div>
      </div>
    );
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 overscroll-contain" role="presentation" onClick={onClose}>
      <div className="w-full max-w-lg bg-surface rounded-xl shadow-lg border border-border overflow-hidden max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        {content}
      </div>
    </div>
  );
}
