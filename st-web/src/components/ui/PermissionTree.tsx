import { useMemo, useState, useCallback, memo, useEffect, useRef } from 'react';
import { Search, ChevronRight, ChevronDown } from 'lucide-react';
import { cn } from '../../lib/utils';
import type { PermissionVO } from '../../types';

interface Props {
  /** 按模块分组的权限定义 */
  groups: Record<string, PermissionVO[]>;
  selectedIds: Set<string>;
  onChange: (ids: Set<string>) => void;
  moduleLabel?: Record<string, string>;
}

const MODULE_LABELS: Record<string, string> = {
  file: '文件',
  share: '分享',
  team: '团队',
  search: '搜索',
  admin: '管理',
  transfer: '传输',
};

/** 原生 checkbox 的半选（indeterminate）视觉，需用 ref 设置 */
const TreeCheckbox = memo(function TreeCheckbox({
  checked,
  indeterminate,
  onChange,
  label,
}: {
  checked: boolean;
  indeterminate: boolean;
  onChange: () => void;
  label: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      checked={checked}
      onChange={onChange}
      aria-label={label}
      className="w-4 h-4 cursor-pointer rounded focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:outline-none"
    />
  );
});

/**
 * 树状权限分配（参考 Ant Design Tree checkable 父子联动）：
 * - 模块为父节点，权限为子节点；
 * - 勾选父节点自动选中全部子权限；子权限部分选中时父节点呈半选（indeterminate）；
 * - 支持权限名称/编码搜索、模块折叠展开；
 * - 权限 id 为字符串，不做 Number() 转换。
 */
export const PermissionTree = memo<Props>(function PermissionTree({ groups, selectedIds, onChange, moduleLabel }) {
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(Object.keys(groups)));

  const labelOf = (mod: string) => moduleLabel?.[mod] ?? MODULE_LABELS[mod] ?? mod;

  const moduleIds = useMemo(() => {
    const map: Record<string, string[]> = {};
    for (const [mod, perms] of Object.entries(groups)) map[mod] = perms.map((p) => p.id);
    return map;
  }, [groups]);

  const allLeafIds = useMemo(() => Object.values(moduleIds).flat(), [moduleIds]);

  const filteredGroups = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return groups;
    const out: Record<string, PermissionVO[]> = {};
    for (const [mod, perms] of Object.entries(groups)) {
      const matched = perms.filter(
        (p) => p.permissionName.toLowerCase().includes(q) || p.permissionCode.toLowerCase().includes(q),
      );
      if (matched.length) out[mod] = matched;
    }
    return out;
  }, [groups, query]);

  const computeModuleState = useCallback(
    (mod: string) => {
      const ids = moduleIds[mod] ?? [];
      if (ids.length === 0) return { checked: false, indeterminate: false, selectedCount: 0, total: 0 };
      const selectedCount = ids.filter((id) => selectedIds.has(id)).length;
      return {
        checked: selectedCount === ids.length,
        indeterminate: selectedCount > 0 && selectedCount < ids.length,
        selectedCount,
        total: ids.length,
      };
    },
    [moduleIds, selectedIds],
  );

  /** 搜索时自动展开命中模块，避免搜到但看不到 */
  const hasQuery = query.trim().length > 0;

  const toggleLeaf = useCallback(
    (id: string) => {
      const next = new Set(selectedIds);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      onChange(next);
    },
    [selectedIds, onChange],
  );

  const toggleModule = useCallback(
    (mod: string) => {
      const ids = moduleIds[mod] ?? [];
      const next = new Set(selectedIds);
      const allSelected = ids.length > 0 && ids.every((id) => next.has(id));
      if (allSelected) ids.forEach((id) => next.delete(id));
      else ids.forEach((id) => next.add(id));
      onChange(next);
    },
    [moduleIds, selectedIds, onChange],
  );

  const toggleAll = useCallback(() => {
    const next = new Set(selectedIds);
    if (allLeafIds.length > 0 && allLeafIds.every((id) => next.has(id))) allLeafIds.forEach((id) => next.delete(id));
    else allLeafIds.forEach((id) => next.add(id));
    onChange(next);
  }, [allLeafIds, selectedIds, onChange]);

  const toggleExpand = useCallback((mod: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(mod)) next.delete(mod);
      else next.add(mod);
      return next;
    });
  }, []);

  const expandAll = useCallback(() => setExpanded(new Set(Object.keys(groups))), [groups]);
  const collapseAll = useCallback(() => setExpanded(new Set()), []);

  const allChecked = allLeafIds.length > 0 && allLeafIds.every((id) => selectedIds.has(id));
  const allIndeterminate = allLeafIds.some((id) => selectedIds.has(id)) && !allChecked;

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted pointer-events-none" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索权限名称或编码"
            aria-label="搜索权限"
            className="input-field pl-9 h-9"
          />
        </div>
        <div className="flex items-center gap-1 text-xs text-muted">
          <button type="button" onClick={expandAll} className="px-2 py-1 rounded hover:bg-surface-2 cursor-pointer">展开</button>
          <button type="button" onClick={collapseAll} className="px-2 py-1 rounded hover:bg-surface-2 cursor-pointer">折叠</button>
        </div>
      </div>

      <div className="flex items-center gap-4 px-1">
        <label className="flex items-center gap-2 text-xs text-muted cursor-pointer select-none">
          <TreeCheckbox checked={allChecked} indeterminate={allIndeterminate} onChange={toggleAll} label="全选权限" />
          全选
        </label>
        <span className="text-xs text-muted">已选 {selectedIds.size} 项</span>
      </div>

      <div className="border border-border rounded-lg overflow-hidden">
        {Object.entries(filteredGroups).length === 0 && (
          <div className="py-10 text-center text-sm text-muted">无匹配权限</div>
        )}
        <div className="divide-y divide-border">
          {Object.entries(filteredGroups).map(([mod, perms]) => {
            const state = computeModuleState(mod);
            const isOpen = hasQuery || expanded.has(mod);
            return (
              <div key={mod}>
                {/* 父节点：模块 */}
                <div className="flex items-center gap-2 px-3 py-2.5 bg-surface-2/60 hover:bg-surface-2 select-none">
                  <button
                    type="button"
                    onClick={() => toggleExpand(mod)}
                    className="p-1 rounded hover:bg-surface-2 cursor-pointer text-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    aria-label={isOpen ? `折叠 ${labelOf(mod)}` : `展开 ${labelOf(mod)}`}
                    aria-expanded={isOpen}
                  >
                    {isOpen ? <ChevronDown className="w-4 h-4" aria-hidden /> : <ChevronRight className="w-4 h-4" aria-hidden />}
                  </button>
                  <TreeCheckbox
                    checked={state.checked}
                    indeterminate={state.indeterminate}
                    onChange={() => toggleModule(mod)}
                    label={`全选${labelOf(mod)}权限`}
                  />
                  <span className="flex-1 text-sm font-medium text-fg">{labelOf(mod)}</span>
                  <span className={cn('text-xs tabular-nums', state.selectedCount > 0 && state.selectedCount < state.total ? 'text-primary-600' : 'text-muted')}>
                    {state.selectedCount}/{state.total}
                  </span>
                </div>

                {/* 子节点：权限 */}
                {isOpen && (
                  <div className="pl-10 pr-3 py-1 space-y-0.5">
                    {perms.map((p) => {
                      const checked = selectedIds.has(p.id);
                      return (
                        <label
                          key={p.id}
                          className={cn(
                            'flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer text-sm transition-colors hover:bg-surface-2',
                            checked && 'bg-primary-500/5',
                          )}
                        >
                          <TreeCheckbox checked={checked} indeterminate={false} onChange={() => toggleLeaf(p.id)} label={p.permissionName} />
                          <span className="text-fg truncate">{p.permissionName}</span>
                          <span className="ml-auto text-xs text-muted font-mono truncate">{p.permissionCode}</span>
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
});
