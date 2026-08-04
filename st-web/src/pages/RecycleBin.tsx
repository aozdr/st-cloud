import { useState, useEffect, useCallback } from 'react';
import api from '../lib/api';
import type { RecycleItem } from '../types';
import { getFileTypeConfig, formatDate, formatSize, cn } from '../lib/utils';
import { Trash2, RotateCcw, AlertTriangle, ChevronDown } from 'lucide-react';
import { useConfirm } from '../components/ui/ConfirmDialog';
import { useStorageStore } from '../store/storage';
import FileTypeIcon from '../components/file/FileTypeIcon';

export default function RecycleBin() {
  const { confirm } = useConfirm();
  const [items, setItems] = useState<RecycleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [lastSelectedId, setLastSelectedId] = useState<string | null>(null);

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<RecycleItem[]>('/recycle/list');
      setItems(res || []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const handleSelect = (id: string, e: React.MouseEvent) => {
    if (e.ctrlKey || e.metaKey) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      });
      setLastSelectedId(id);
    } else if (e.shiftKey && lastSelectedId) {
      const ids = items.map((i) => i.id);
      const start = ids.indexOf(lastSelectedId);
      const end = ids.indexOf(id);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        setSelectedIds(new Set(ids.slice(from, to + 1)));
      } else {
        setSelectedIds(new Set([id]));
      }
    } else {
      setSelectedIds(new Set([id]));
      setLastSelectedId(id);
    }
  };

  const selectAll = () => {
    setSelectedIds(new Set(items.map((i) => i.id)));
    setLastSelectedId(null);
  };

  const clearSelection = () => {
    setSelectedIds(new Set());
    setLastSelectedId(null);
  };

  const handleRestore = async (ids: string[]) => {
    try {
      await api.post('/recycle/restore', { nodeIds: ids });
      fetchItems();
      setSelectedIds(new Set());
    } catch (err) {
      console.error('Restore failed:', err);
    }
  };

  const handlePermanentDelete = async (ids: string[]) => {
    const confirmed = await confirm({
      title: '永久删除',
      message: `确定永久删除选中的 ${ids.length} 个文件？此操作不可恢复。`,
      confirmText: '永久删除',
      danger: true,
    });
    if (!confirmed) return;
    try {
      await api.post('/recycle/delete', { nodeIds: ids });
      fetchItems();
      setSelectedIds(new Set());
      useStorageStore.getState().fetchStorage();
    } catch (err) {
      console.error('Delete failed:', err);
    }
  };

  const handleEmpty = async () => {
    const confirmed = await confirm({
      title: '清空回收站',
      message: '确定清空回收站？所有文件将被永久删除，此操作不可恢复。',
      confirmText: '清空',
      danger: true,
    });
    if (!confirmed) return;
    try {
      await api.post('/recycle/empty');
      fetchItems();
      setSelectedIds(new Set());
      useStorageStore.getState().fetchStorage();
    } catch (err) {
      console.error('Empty recycle bin failed:', err);
    }
  };

  const allSelected = items.length > 0 && items.every((i) => selectedIds.has(i.id));

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-6 py-3 border-b border-stone-200 bg-white">
        <div className="flex items-center gap-2">
          <h2 className="text-base font-semibold text-stone-900">回收站</h2>
          <span className="text-sm text-stone-400">({items.length})</span>
        </div>
        <div className="flex items-center gap-2">
          {selectedIds.size > 0 && (
            <>
              <button
                onClick={() => handleRestore([...selectedIds])}
                className="btn-ghost"
              >
                <RotateCcw className="w-4 h-4" />
                <span>恢复</span>
              </button>
              <button
                onClick={() => handlePermanentDelete([...selectedIds])}
                className="btn-ghost text-red-600 hover:bg-red-50"
              >
                <Trash2 className="w-4 h-4" />
                <span>永久删除</span>
              </button>
              <div className="w-px h-5 bg-stone-200 mx-1" />
            </>
          )}
          {items.length > 0 && (
            <button
              onClick={handleEmpty}
              className="btn-danger"
            >
              <Trash2 className="w-4 h-4" />
              <span>清空回收站</span>
            </button>
          )}
        </div>
      </div>

      {/* List */}
      <div
        className="flex-1 overflow-auto px-6 py-4"
        onMouseDown={(e) => {
          if (e.target === e.currentTarget) clearSelection();
        }}
      >
        {loading ? (
          <div className="flex items-center justify-center h-full">
            <div className="w-8 h-8 border-3 border-primary-600 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full py-20">
            <div className="w-24 h-24 bg-stone-50 rounded-2xl flex items-center justify-center mb-4">
              <Trash2 className="w-10 h-10 text-stone-300" />
            </div>
            <h3 className="text-base font-medium text-stone-900 mb-1">回收站为空</h3>
            <p className="text-sm text-stone-500">删除的文件将在此显示，30天后自动清理</p>
          </div>
        ) : (
          <div className="bg-white rounded-lg border border-stone-200 overflow-hidden">
            <table className="w-full">
              <thead>
                <tr className="border-b border-stone-200 bg-stone-50">
                  <th className="w-10 px-4 py-2.5">
                    <button
                      onClick={selectAll}
                      className={cn(
                        'w-4 h-4 rounded border flex items-center justify-center cursor-pointer transition-colors',
                        allSelected ? 'bg-primary-600 border-primary-600' : 'border-stone-300 hover:border-primary-500'
                      )}
                    >
                      {allSelected && <ChevronDown className="w-3 h-3 text-white" />}
                    </button>
                  </th>
                  <th className="text-left text-xs font-medium text-stone-500 px-3 py-2.5">名称</th>
                  <th className="text-left text-xs font-medium text-stone-500 px-3 py-2.5 w-40">原路径</th>
                  <th className="text-left text-xs font-medium text-stone-500 px-3 py-2.5 w-36">删除时间</th>
                  <th className="text-left text-xs font-medium text-stone-500 px-3 py-2.5 w-24">剩余天数</th>
                  <th className="text-right text-xs font-medium text-stone-500 px-3 py-2.5 w-28">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const config = getFileTypeConfig(item.nodeType, null);
                  const isSelected = selectedIds.has(item.id);
                  const dotIdx = item.name.lastIndexOf('.');
                  const itemSuffix = item.nodeType === 0 || dotIdx < 1 ? null : item.name.slice(dotIdx + 1);

                  return (
                    <tr
                      key={item.id}
                      onClick={(e) => handleSelect(item.id, e)}
                      className={cn(
                        'border-b border-stone-100 last:border-0 cursor-pointer transition-colors',
                        isSelected ? 'bg-primary-50/60' : 'hover:bg-stone-50'
                      )}
                    >
                      <td className="px-4 py-2.5">
                        <div
                          className={cn(
                            'w-4 h-4 rounded border flex items-center justify-center transition-colors',
                            isSelected ? 'bg-primary-600 border-primary-600' : 'border-stone-300 bg-white'
                          )}
                        >
                          {isSelected && <div className="w-2 h-2 bg-white rounded-sm" />}
                        </div>
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="flex items-center gap-2.5 min-w-0">
                          <FileTypeIcon config={config} size="sm" isFolder={item.nodeType === 0} suffix={itemSuffix} />
                          <div className="min-w-0">
                            <div className="text-sm text-stone-900 truncate">{item.name}</div>
                            {item.fileSize && (
                              <div className="text-xs text-stone-400">{formatSize(item.fileSize)}</div>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="px-3 py-2.5 text-sm text-stone-400 truncate">{item.path}</td>
                      <td className="px-3 py-2.5 text-sm text-stone-500">{formatDate(item.updatedAt)}</td>
                      <td className="px-3 py-2.5">
                        {item.remainingDays > 0 ? (
                          <span className="text-sm text-stone-500">{item.remainingDays} 天</span>
                        ) : (
                          <span className="text-xs text-red-600 flex items-center gap-1">
                            <AlertTriangle className="w-3 h-3" />
                            即将清理
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleRestore([item.id]); }}
                            className="p-1.5 text-stone-500 hover:text-primary-600 hover:bg-primary-50 rounded-lg cursor-pointer transition-colors"
                            title="恢复"
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                          <button
                            onClick={(e) => { e.stopPropagation(); handlePermanentDelete([item.id]); }}
                            className="p-1.5 text-stone-500 hover:text-red-600 hover:bg-red-50 rounded-lg cursor-pointer transition-colors"
                            title="永久删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
