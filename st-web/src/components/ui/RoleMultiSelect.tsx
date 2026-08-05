import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown, Search, X, Check } from 'lucide-react';
import type { RoleVO } from '../../types';

interface RoleMultiSelectProps {
  roles: RoleVO[];
  selectedIds: Set<string>;
  onChange: (ids: Set<string>) => void;
  placeholder?: string;
}

const DATA_SCOPE_LABELS: Record<number, { label: string; cls: string }> = {
  1: { label: '本人', cls: 'text-stone-500 bg-stone-100' },
  2: { label: '租户', cls: 'text-blue-600 bg-blue-50' },
  3: { label: '全部', cls: 'text-red-600 bg-red-50' },
};

export function RoleMultiSelect({ roles, selectedIds, onChange, placeholder = '选择角色' }: RoleMultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null);
  const triggerRef = useRef<HTMLDivElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (open && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      const dropdownMaxH = 320;
      const top = spaceBelow < dropdownMaxH && rect.top > dropdownMaxH
        ? rect.top - dropdownMaxH - 4
        : rect.bottom + 4;
      setPos({ top, left: rect.left, width: rect.width });
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        triggerRef.current && !triggerRef.current.contains(target) &&
        dropdownRef.current && !dropdownRef.current.contains(target)
      ) {
        setOpen(false);
      }
    };
    const reposition = () => {
      if (triggerRef.current) {
        const rect = triggerRef.current.getBoundingClientRect();
        setPos({ top: rect.bottom + 4, left: rect.left, width: rect.width });
      }
    };
    document.addEventListener('mousedown', handler);
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    return () => {
      document.removeEventListener('mousedown', handler);
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
    };
  }, [open]);

  const toggle = (id: string) => {
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onChange(next);
  };

  const q = query.trim().toLowerCase();
  const filtered = roles.filter(
    (r) => !q || r.roleName.toLowerCase().includes(q) || r.roleCode.toLowerCase().includes(q),
  );

  const selectedRoles = roles.filter((r) => selectedIds.has(r.id));

  return (
    <div>
      <div
        ref={triggerRef}
        onClick={() => setOpen((v) => !v)}
        className="input-field flex items-center flex-wrap gap-1.5 min-h-[42px] cursor-pointer"
      >
        {selectedRoles.length === 0 && <span className="text-stone-400 text-sm">{placeholder}</span>}
        {selectedRoles.map((r) => (
          <span
            key={r.id}
            className="inline-flex items-center gap-1 text-xs bg-primary-50 text-primary-700 px-2 py-0.5 rounded"
          >
            {r.roleName}
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                toggle(r.id);
              }}
              className="hover:text-primary-900 cursor-pointer"
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
        <ChevronDown className="w-4 h-4 text-stone-400 ml-auto flex-shrink-0" />
      </div>

      {open && pos && createPortal(
        <div
          ref={dropdownRef}
          style={{ position: 'fixed', top: pos.top, left: pos.left, width: pos.width, zIndex: 9999 }}
          className="bg-white border border-stone-200 rounded-md shadow-lg max-h-80 overflow-hidden flex flex-col"
        >
          <div className="p-2 border-b border-stone-100">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-4 h-4 text-stone-400 pointer-events-none" />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="搜索角色名称或编码"
                className="w-full pl-8 pr-3 py-1.5 text-sm border border-stone-200 rounded-md focus:outline-none focus:border-primary-500"
                autoFocus
              />
            </div>
          </div>
          <div className="overflow-y-auto flex-1">
            {filtered.length === 0 && (
              <div className="py-4 text-center text-xs text-stone-400">无匹配角色</div>
            )}
            {filtered.map((r) => {
              const ds = DATA_SCOPE_LABELS[r.dataScope] || DATA_SCOPE_LABELS[1];
              const checked = selectedIds.has(r.id);
              return (
                <div
                  key={r.id}
                  onClick={() => toggle(r.id)}
                  className="flex items-center gap-2 px-3 py-2 hover:bg-stone-50 cursor-pointer transition-colors"
                >
                  <div
                    className={`w-4 h-4 rounded border flex items-center justify-center flex-shrink-0 ${
                      checked ? 'bg-primary-600 border-primary-600' : 'border-stone-300'
                    }`}
                  >
                    {checked && <Check className="w-3 h-3 text-white" />}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1.5">
                      <span className="text-sm text-stone-800 truncate">{r.roleName}</span>
                      {r.builtIn && <span className="text-xs text-primary-600">内置</span>}
                    </div>
                    <span className="text-xs text-stone-400 font-mono">{r.roleCode}</span>
                  </div>
                  <span className={`text-xs px-1.5 py-0.5 rounded flex-shrink-0 ${ds.cls}`}>{ds.label}</span>
                </div>
              );
            })}
          </div>
        </div>,
        document.body,
      )}
    </div>
  );
}