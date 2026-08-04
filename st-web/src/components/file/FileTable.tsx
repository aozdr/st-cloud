import { getFileTypeConfig, formatSize, formatDate, cn } from '../../lib/utils';
import type { FileNode } from '../../types';
import { Check } from 'lucide-react';
import FileTypeIcon from './FileTypeIcon';

interface Props {
  files: FileNode[];
  selectedIds: Set<string>;
  focusedId: string | null;
  cutIds: Set<string> | null;
  onSelect: (id: string, e: React.MouseEvent) => void;
  onSelectAll: () => void;
  onContextMenu: (e: React.MouseEvent, node: FileNode) => void;
  onNavigate: (node: FileNode) => void;
  onDoubleClick: (node: FileNode) => void;
}

export default function FileTable({ files, selectedIds, focusedId, cutIds, onSelect, onSelectAll, onContextMenu, onDoubleClick }: Props) {
  const allSelected = files.length > 0 && files.every((f) => selectedIds.has(f.id));
  const someSelected = files.some((f) => selectedIds.has(f.id));

  return (
    <div className="overflow-hidden rounded-xl border border-stone-200/80 bg-white">
      <table className="w-full">
        <thead>
          <tr className="border-b border-stone-100 bg-stone-50/60">
            <th className="w-12 px-4 py-3">
              <button
                onClick={onSelectAll}
                className={cn(
                  'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center cursor-pointer transition-colors',
                  allSelected
                    ? 'bg-primary-600 border-primary-600'
                    : someSelected
                      ? 'bg-primary-100 border-primary-400'
                      : 'border-stone-300 hover:border-primary-500'
                )}
              >
                {allSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} />}
                {someSelected && !allSelected && <div className="w-2 h-0.5 bg-primary-600 rounded" />}
              </button>
            </th>
            <th className="text-left text-xs font-semibold uppercase tracking-wide text-stone-400 px-3 py-3">名称</th>
            <th className="text-left text-xs font-semibold uppercase tracking-wide text-stone-400 px-3 py-3 w-24">类型</th>
            <th className="text-left text-xs font-semibold uppercase tracking-wide text-stone-400 px-3 py-3 w-28">大小</th>
            <th className="text-left text-xs font-semibold uppercase tracking-wide text-stone-400 px-3 py-3 w-40">修改时间</th>
          </tr>
        </thead>
        <tbody>
          {files.map((file) => {
            const config = getFileTypeConfig(file.nodeType, file.suffix);
            const isSelected = selectedIds.has(file.id);

            return (
              <tr
                key={file.id}
                data-file-id={file.id}
                onClick={(e) => onSelect(file.id, e)}
                onDoubleClick={() => onDoubleClick(file)}
                onContextMenu={(e) => onContextMenu(e, file)}
                className={cn(
                  'border-b border-stone-50 last:border-0 cursor-pointer transition-colors duration-100',
                  isSelected
                    ? 'bg-primary-50/60'
                    : '',
                  focusedId === file.id && !isSelected && 'bg-primary-50/30',
                  cutIds?.has(file.id) && 'opacity-50'
                )}
              >
                <td className="px-4 py-2.5">
                  <div
                    className={cn(
                      'w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center transition-colors',
                      isSelected ? 'bg-primary-600 border-primary-600' : 'border-stone-300 bg-white'
                    )}
                  >
                    {isSelected && <Check className="w-3 h-3 text-white" strokeWidth={3} />}
                  </div>
                </td>
                <td className="px-3 py-2.5">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <FileTypeIcon config={config} size="sm" isFolder={file.nodeType === 0} suffix={file.suffix} />
                    <span className={cn(
                      'text-sm truncate',
                      isSelected ? 'text-primary-700 font-medium' : 'text-stone-700'
                    )}>
                      {file.name}
                    </span>
                  </div>
                </td>
                <td className="px-3 py-2.5">
                  <span className="text-xs text-stone-500">
                    {file.nodeType === 0 ? '文件夹' : config.label}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-sm text-stone-500 tabular-nums">
                  {file.nodeType === 0 ? '-' : formatSize(file.fileSize)}
                </td>
                <td className="px-3 py-2.5 text-sm text-stone-500 tabular-nums">{formatDate(file.updatedAt)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
