import { useEffect, useRef } from 'react';
import { isElectron } from '../lib/electron';
import type { FileNode } from '../types';


export interface FileKeyboardState {
  files: FileNode[];
  selectedIds: Set<string>;
  clipboard: { nodeIds: string[]; mode: 'copy' | 'cut' } | null;
  view: 'list' | 'grid';
  parentId: string | null;
  focusedIndex: number;
  lastSelectedId: string | null;
}

export interface FileKeyboardActions {
  setSelectedIds: React.Dispatch<React.SetStateAction<Set<string>>>;
  setClipboard: React.Dispatch<React.SetStateAction<{ nodeIds: string[]; mode: 'copy' | 'cut' } | null>>;
  setFocusedIndex: React.Dispatch<React.SetStateAction<number>>;
  setLastSelectedId: React.Dispatch<React.SetStateAction<string | null>>;
  setRenameTarget: React.Dispatch<React.SetStateAction<FileNode | null>>;
  setPreview: React.Dispatch<React.SetStateAction<{ files: FileNode[]; index: number } | null>>;
  setContextMenu: React.Dispatch<React.SetStateAction<{ x: number; y: number; node: FileNode } | null>>;
  setBlankContextMenu: React.Dispatch<React.SetStateAction<{ x: number; y: number } | null>>;
  setShowCreateFolder: React.Dispatch<React.SetStateAction<boolean>>;
  selectAll: () => void;
  clearSelection: () => void;
  moveFocus: (delta: number, extendSelection: boolean) => void;
  refresh: () => void;
  navigate: (delta: number) => void;
  onBack?: () => void;
  onNavigateFolder: (node: FileNode) => void;
  handlePaste: () => void;
  handleDelete: (nodeIds: string[]) => void;
  showToast: (msg: string, type?: 'success' | 'error' | 'info' | 'warning') => void;
  hasPermission: (perm: string) => boolean;
}

/**
 * 文件列表键盘导航：Ctrl+A/C/X/V、Delete、F2、Enter、方向键、Home/End、
 * Backspace 返回上级、Alt+←/→ 前进后退、字母快速定位。
 * 通过 ref 读取最新 state，监听器只注册一次。
 */
export function useFileKeyboard(
  getState: () => FileKeyboardState,
  actions: FileKeyboardActions,
) {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;

  const searchBuffer = useRef('');
  const searchTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const a = actionsRef.current;
      const st = getState();

      const target = e.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;
      const hasTextSelection = (window.getSelection()?.toString().length ?? 0) > 0;

      if (e.ctrlKey || e.metaKey) {
        switch (e.key.toLowerCase()) {
          case 'a':
            e.preventDefault();
            a.selectAll();
            a.showToast(`已选中 ${st.files.length} 项`);
            return;
          case 'c':
            if (!hasTextSelection && st.selectedIds.size > 0) {
              e.preventDefault();
              a.setClipboard({ nodeIds: [...st.selectedIds], mode: 'copy' });
              a.showToast(`已复制 ${st.selectedIds.size} 项`);
            }
            return;
          case 'x':
            if (!hasTextSelection && st.selectedIds.size > 0) {
              e.preventDefault();
              a.setClipboard({ nodeIds: [...st.selectedIds], mode: 'cut' });
              a.showToast(`已剪贴 ${st.selectedIds.size} 项`);
            }
            return;
          case 'v':
            if (st.clipboard) {
              e.preventDefault();
              a.handlePaste();
            }
            return;
          case 'n':
            if (e.shiftKey) {
              e.preventDefault();
              a.setShowCreateFolder(true);
            }
            return;
        }
        return;
      }

      if (e.altKey) {
        if (e.key === 'ArrowLeft') {
          e.preventDefault();
          a.onBack?.();
        } else if (e.key === 'ArrowRight') {
          e.preventDefault();
          a.navigate(1);
        }
        return;
      }

      switch (e.key) {
        case 'Escape':
          a.clearSelection();
          a.setContextMenu(null);
          a.setBlankContextMenu(null);
          a.setFocusedIndex(-1);
          return;
        case 'Delete':
          if (st.selectedIds.size > 0) a.handleDelete([...st.selectedIds]);
          return;
        case 'F2':
          if (st.selectedIds.size === 1) {
            const node = st.files.find((f) => st.selectedIds.has(f.id));
            if (node) a.setRenameTarget(node);
          }
          return;
        case 'F5':
          if (isElectron()) {
            e.preventDefault();
            a.refresh();
          }
          return;
        case 'Enter':
          if (st.selectedIds.size === 1) {
            const node = st.files.find((f) => st.selectedIds.has(f.id));
            if (node) {
              if (node.nodeType === 0) {
                a.onNavigateFolder(node);
              } else if (a.hasPermission('file:preview')) {
                const fileFiles = st.files.filter((f) => f.nodeType === 1);
                const idx = fileFiles.findIndex((f) => f.id === node.id);
                a.setPreview({ files: fileFiles, index: idx >= 0 ? idx : 0 });
              }
            }
          }
          return;
        case 'Backspace':
          if (st.parentId && st.parentId !== '0') {
            e.preventDefault();
            a.onBack?.();
          }
          return;
        case 'ArrowDown':
          e.preventDefault();
          a.moveFocus(1, e.shiftKey);
          return;
        case 'ArrowUp':
          e.preventDefault();
          a.moveFocus(-1, e.shiftKey);
          return;
        case 'ArrowRight':
          if (st.view === 'grid') {
            e.preventDefault();
            a.moveFocus(1, e.shiftKey);
          }
          return;
        case 'ArrowLeft':
          if (st.view === 'grid') {
            e.preventDefault();
            a.moveFocus(-1, e.shiftKey);
          }
          return;
        case 'Home':
          e.preventDefault();
          if (st.files.length > 0) {
            a.setFocusedIndex(0);
            a.setSelectedIds(new Set([st.files[0].id]));
            a.setLastSelectedId(st.files[0].id);
          }
          return;
        case 'End':
          e.preventDefault();
          if (st.files.length > 0) {
            const last = st.files.length - 1;
            a.setFocusedIndex(last);
            a.setSelectedIds(new Set([st.files[last].id]));
            a.setLastSelectedId(st.files[last].id);
          }
          return;
      }

      if (e.key.length === 1 && /[a-zA-Z0-9\u4e00-\u9fa5]/.test(e.key)) {
        if (searchTimeout.current) clearTimeout(searchTimeout.current);
        searchBuffer.current += e.key.toLowerCase();
        searchTimeout.current = setTimeout(() => { searchBuffer.current = ''; }, 1000);

        const match = st.files.findIndex((f) => f.name.toLowerCase().startsWith(searchBuffer.current));
        if (match >= 0) {
          a.setFocusedIndex(match);
          a.setSelectedIds(new Set([st.files[match].id]));
          a.setLastSelectedId(st.files[match].id);
        }
      }
    };

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [getState]);
}