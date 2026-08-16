import { useCallback, useRef, useState } from 'react';
import type { FileSource } from '../lib/fileSource';

export interface FileClipboardState {
  clipboard: { nodeIds: string[]; mode: 'copy' | 'cut' } | null;
  setClipboard: React.Dispatch<React.SetStateAction<{ nodeIds: string[]; mode: 'copy' | 'cut' } | null>>;
  /** 粘贴当前剪贴板内容到当前目录 */
  paste: () => void;
}

/**
 * 文件剪贴板（复制/剪切/粘贴）。通过 ref 读取最新状态，paste 引用保持稳定。
 */
export function useFileClipboard(
  source: FileSource,
  parentId: string | null,
  showToast: (msg: string, type?: 'success' | 'error' | 'info' | 'warning') => void,
  onPasted: () => void,
): FileClipboardState {
  const [clipboard, setClipboard] = useState<{ nodeIds: string[]; mode: 'copy' | 'cut' } | null>(null);
  const stateRef = useRef({ clipboard, source, parentId });
  stateRef.current = { clipboard, source, parentId };

  const paste = useCallback(async () => {
    const st = stateRef.current;
    if (!st.clipboard || st.clipboard.nodeIds.length === 0) return;
    try {
      if (st.clipboard.mode === 'copy') {
        await st.source.copy(st.clipboard.nodeIds, st.parentId || '0');
        showToast(`已粘贴 ${st.clipboard.nodeIds.length} 项`);
      } else {
        await st.source.move(st.clipboard.nodeIds, st.parentId || '0');
        showToast(`已移动 ${st.clipboard.nodeIds.length} 项`);
        setClipboard(null);
      }
      onPasted();
    } catch (err) {
      console.error('Paste failed:', err);
      showToast('粘贴失败', 'error');
    }
  }, [showToast, onPasted]);

  return { clipboard, setClipboard, paste };
}
