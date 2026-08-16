import { useCallback, useState } from 'react';
import type { FileNode } from '../types';

export interface FileSelection {
  selectedIds: Set<string>;
  focusedIndex: number;
  lastSelectedId: string | null;
  mobileSelectMode: boolean;
  setMobileSelectMode: (v: boolean) => void;
  setSelectedIds: React.Dispatch<React.SetStateAction<Set<string>>>;
  setFocusedIndex: React.Dispatch<React.SetStateAction<number>>;
  setLastSelectedId: React.Dispatch<React.SetStateAction<string | null>>;
  handleSelect: (id: string, e: React.MouseEvent) => void;
  toggleSelect: (id: string) => void;
  selectAll: () => void;
  clearSelection: () => void;
  moveFocus: (delta: number, extendSelection: boolean) => void;
  handleMobileLongPress: (node: FileNode) => void;
  handleMobileClick: (node: FileNode) => void;
}

/**
 * 文件列表选择状态：单选/多选/Ctrl/Shift 范围选择、移动端多选模式、键盘焦点导航。
 */
export function useFileSelection(files: FileNode[], isMobile: boolean): FileSelection {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const [lastSelectedId, setLastSelectedId] = useState<string | null>(null);
  const [mobileSelectMode, setMobileSelectMode] = useState(false);

  const handleSelect = useCallback((id: string, e: React.MouseEvent) => {
    // 移动端多选模式：单击切换选中
    if (isMobile && mobileSelectMode) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      });
      setLastSelectedId(id);
      return;
    }
    if (e.ctrlKey || e.metaKey) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      });
      setLastSelectedId(id);
    } else if (e.shiftKey && lastSelectedId) {
      const ids = files.map((f) => f.id);
      const start = ids.indexOf(lastSelectedId);
      const end = ids.indexOf(id);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        setSelectedIds(new Set(ids.slice(from, to + 1)));
      } else {
        setSelectedIds(new Set([id]));
      }
    } else {
      // 普通点击：若该项已选中且仅选中一项，则取消选中；否则仅选中该项
      if (selectedIds.has(id) && selectedIds.size === 1) {
        setSelectedIds(new Set());
        setLastSelectedId(null);
      } else {
        setSelectedIds(new Set([id]));
        setLastSelectedId(id);
      }
    }
  }, [isMobile, mobileSelectMode, files, lastSelectedId, selectedIds]);

  const toggleSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setLastSelectedId(id);
  }, []);

  const selectAll = useCallback(() => {
    setSelectedIds(new Set(files.map((f) => f.id)));
    setLastSelectedId(null);
  }, [files]);

  const clearSelection = useCallback(() => {
    setSelectedIds(new Set());
    setLastSelectedId(null);
  }, []);

  const moveFocus = useCallback((delta: number, extendSelection: boolean) => {
    if (files.length === 0) return;
    let newIndex = focusedIndex >= 0 ? focusedIndex + delta : 0;
    newIndex = Math.max(0, Math.min(files.length - 1, newIndex));
    setFocusedIndex(newIndex);
    const newId = files[newIndex].id;
    if (extendSelection) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        next.add(newId);
        return next;
      });
    } else {
      setSelectedIds(new Set([newId]));
      setLastSelectedId(newId);
    }
  }, [files, focusedIndex]);

  const handleMobileLongPress = useCallback((node: FileNode) => {
    if (!isMobile) return;
    setMobileSelectMode(true);
    setSelectedIds((prev) => {
      const next = new Set(prev);
      next.add(node.id);
      return next;
    });
    setLastSelectedId(node.id);
  }, [isMobile]);

  const handleMobileClick = useCallback((node: FileNode) => {
    if (!isMobile || !mobileSelectMode) return;
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(node.id)) next.delete(node.id);
      else next.add(node.id);
      return next;
    });
  }, [isMobile, mobileSelectMode]);

  return {
    selectedIds,
    focusedIndex,
    lastSelectedId,
    mobileSelectMode,
    setMobileSelectMode,
    setSelectedIds,
    setFocusedIndex,
    setLastSelectedId,
    handleSelect,
    toggleSelect,
    selectAll,
    clearSelection,
    moveFocus,
    handleMobileLongPress,
    handleMobileClick,
  };
}
