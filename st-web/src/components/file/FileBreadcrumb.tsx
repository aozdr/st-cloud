import { type RefObject } from 'react';
import { Home, ChevronRight, Pencil, X } from 'lucide-react';
import { cn } from '../../lib/utils';

interface PathSegment {
  name: string;
  path: string;
}

interface FileBreadcrumbProps {
  currentPath: string;
  pathEditMode: boolean;
  setPathEditMode: (v: boolean) => void;
  pathInput: string;
  setPathInput: (v: string) => void;
  pathError: boolean;
  setPathError: (v: boolean) => void;
  onPathSubmit: () => void;
  onEnterEditMode: () => void;
  pathSegments: PathSegment[];
  onNavigateToPath: (path: string) => void;
  pathInputRef: RefObject<HTMLInputElement>;
}

export default function FileBreadcrumb({
  currentPath, pathEditMode, setPathEditMode,
  pathInput, setPathInput, pathError, setPathError,
  onPathSubmit, onEnterEditMode,
  pathSegments, onNavigateToPath, pathInputRef,
}: FileBreadcrumbProps) {
  // UI_DESIGN_SPEC §16：32px 高、13px 字体、当前段加粗
  return (
    <div className="flex items-center gap-2 h-8">
      {pathEditMode ? (
        <div className={cn('flex items-center gap-2 flex-1 bg-surface border rounded-md px-3 py-1.5 ring-2 ring-primary-100', pathError ? 'border-red-400' : 'border-primary-400')}>
          <input
            ref={pathInputRef}
            value={pathInput}
            onChange={(e) => { setPathInput(e.target.value); setPathError(false); }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { e.preventDefault(); onPathSubmit(); }
              else if (e.key === 'Escape') { setPathEditMode(false); setPathError(false); }
            }}
            onBlur={() => { if (pathInput === currentPath) setPathEditMode(false); }}
            aria-label="路径输入"
            className={cn(
              'flex-1 text-sm bg-transparent px-1 py-1.5 outline-none transition-colors',
              pathError ? 'text-red-600 dark:text-red-400' : 'text-fg',
            )}
            placeholder="/folder1/folder2"
            spellCheck={false}
          />
          <button
            onClick={onPathSubmit}
            className="text-xs text-white bg-primary-600 rounded-md px-2 py-1 hover:bg-primary-700 cursor-pointer whitespace-nowrap focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            转到
          </button>
          <button
            onClick={() => { setPathEditMode(false); setPathError(false); }}
            aria-label="取消"
            className="text-muted hover:text-fg cursor-pointer p-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
          >
            <X className="w-4 h-4" aria-hidden />
          </button>
        </div>
      ) : (
        <div
          className="group flex items-center gap-0.5 flex-1 min-w-0 overflow-hidden"
          role="navigation"
          aria-label="路径导航"
          title="点击右侧图标可编辑路径"
        >
          <button
            onClick={(e) => { e.stopPropagation(); onNavigateToPath('/'); }}
            aria-label="根目录"
            title="根目录"
            className="flex items-center gap-1 px-1.5 py-1 rounded-md text-muted hover:bg-surface-2 hover:text-primary-600 transition-colors flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Home className="w-4 h-4" aria-hidden />
          </button>
          {pathSegments.length === 0 ? (
            <span className="text-[13px] text-fg px-1.5 font-medium">根目录</span>
          ) : (
            pathSegments.map((seg, idx) => (
              <div key={seg.path} className="flex items-center gap-0.5 min-w-0">
                <ChevronRight className="w-3.5 h-3.5 text-disabled mx-1.5 flex-shrink-0" aria-hidden />
                <button
                  onClick={(e) => { e.stopPropagation(); onNavigateToPath(seg.path); }}
                  className={cn(
                    'px-1.5 py-1 rounded-md text-[13px] transition-colors truncate focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    idx === pathSegments.length - 1
                      ? 'text-fg font-medium'
                      : 'text-muted hover:bg-surface-2 hover:text-primary-600',
                  )}
                  title={seg.name}
                >
                  {seg.name}
                </button>
              </div>
            ))
          )}
          <div className="flex-1" />
          <button
            onClick={onEnterEditMode}
            aria-label="编辑路径"
            title="编辑路径"
            className="flex items-center justify-center w-7 h-7 rounded-md text-muted/40 hover:text-primary-600 hover:bg-surface-2 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Pencil className="w-3.5 h-3.5" aria-hidden />
          </button>
        </div>
      )}
    </div>
  );
}
