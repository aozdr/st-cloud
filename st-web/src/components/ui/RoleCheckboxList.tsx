import { useMemo, useState, useCallback, memo } from 'react';
import { Search } from 'lucide-react';
import { cn } from '../../lib/utils';
import type { RoleVO } from '../../types';

interface Props {
  roles: RoleVO[];
  selectedIds: Set<string>;
  onChange: (ids: Set<string>) => void;
  placeholder?: string;
  emptyText?: string;
}

/**
 * 角色多选：搜索 + 全选 + 勾选列表。替代原先难用的自定义下拉。
 * 角色 id 为 snowflake 字符串，全程字符串传输，不做 Number() 转换。
 */
export const RoleCheckboxList = memo<Props>(function RoleCheckboxList({
  roles,
  selectedIds,
  onChange,
  placeholder = '搜索角色名称或编码',
  emptyText = '暂无角色',
}) {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return roles;
    return roles.filter(
      (r) => r.roleName.toLowerCase().includes(q) || r.roleCode.toLowerCase().includes(q),
    );
  }, [roles, query]);

  const allSelected = roles.length > 0 && roles.every((r) => selectedIds.has(r.id));

  const toggle = useCallback(
    (id: string) => {
      const next = new Set(selectedIds);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      onChange(next);
    },
    [selectedIds, onChange],
  );

  const toggleAll = useCallback(() => {
    const next = new Set(selectedIds);
    if (allSelected) roles.forEach((r) => next.delete(r.id));
    else roles.forEach((r) => next.add(r.id));
    onChange(next);
  }, [allSelected, roles, selectedIds, onChange]);

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted pointer-events-none" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={placeholder}
            aria-label="搜索角色"
            className="input-field pl-9 h-9"
          />
        </div>
        <label className="flex items-center gap-2 text-xs text-muted cursor-pointer select-none whitespace-nowrap">
          <input
            type="checkbox"
            checked={allSelected}
            onChange={toggleAll}
            className="w-4 h-4 cursor-pointer"
            aria-label="全选角色"
          />
          全选
        </label>
      </div>

      <div className="border border-border rounded-lg max-h-60 overflow-y-auto divide-y divide-border">
        {filtered.length === 0 && (
          <div className="py-8 text-center text-sm text-muted">{emptyText}</div>
        )}
        {filtered.map((r) => {
          const checked = selectedIds.has(r.id);
          return (
            <label
              key={r.id}
              className={cn(
                'flex items-center gap-3 px-3 py-2.5 cursor-pointer transition-colors hover:bg-surface-2',
                checked && 'bg-primary-500/5',
              )}
            >
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggle(r.id)}
                className="w-4 h-4 cursor-pointer"
              />
              <span className="flex-1 min-w-0">
                <span className="block text-sm text-fg truncate">{r.roleName}</span>
                <span className="block text-xs text-muted font-mono truncate">{r.roleCode}</span>
              </span>
              {r.builtIn && (
                <span className="text-xs text-primary-600 bg-primary-500/10 px-2 py-0.5 rounded">
                  内置
                </span>
              )}
            </label>
          );
        })}
      </div>
    </div>
  );
});
