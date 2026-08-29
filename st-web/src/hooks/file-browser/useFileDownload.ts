import { useCallback } from 'react';
import { isElectron } from '../../lib/electron';
import { useTransferStore } from '../../store/transfer';
import type { FileNode } from '../../types';
import type { FileSource } from '../../lib/fileSource';
import type { useToast } from '../../components/ui/Toast';

type ShowToast = ReturnType<typeof useToast>['showToast'];

/**
 * 文件浏览下载模块：桌面端直下/队列下载、浏览器单文件 a 标签下载、多文件打包 zip。
 * 拆分自 useFileBrowser.ts，行为保持不变。
 */
export function useFileDownload({
  source, files, showToast,
  setDownloadTarget, setZipProgress, setDownloadQueuedCount,
}: {
  source: FileSource;
  files: FileNode[];
  showToast: ShowToast;
  setDownloadTarget: (v: FileNode | null) => void;
  setZipProgress: (v: number | null) => void;
  setDownloadQueuedCount: (v: number | null) => void;
}) {
  const handleDownload = useCallback(async (nodeIds: string[]) => {
    try {
      if (isElectron()) {
        const nodes = nodeIds
          .map((id) => files.find((f) => f.id === id))
          .filter((n): n is FileNode => !!n);
        const fileNodes = nodes.filter((n) => n.nodeType === 1);
        if (nodes.length > 0 && fileNodes.length === nodes.length) {
          if (nodes.length === 1) {
            setDownloadTarget(nodes[0]);
          } else {
            const downloadsDir = await window.electronAPI!.getDownloadsPath();
            const dir = downloadsDir || '';
            for (const n of nodes) {
              await window.electronAPI!.startDownload(n.id, n.name, Number(n.fileSize || 0), `${dir}\\${n.name}`);
            }
            setDownloadQueuedCount(nodes.length);
          }
          return;
        }
      }
      if (nodeIds.length === 1) {
        const node = files.find((f) => f.id === nodeIds[0]);
        // 单个文件夹：单文件下载接口不支持，改走 ZIP 打包下载
        if (node && node.nodeType === 0) {
          setZipProgress(0);
          try {
            showToast('正在打包下载，请稍候…', 'info', 'zip-download');
            const blob = await source.downloadZip(nodeIds, (loaded) => setZipProgress(loaded));
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `${node.name}.zip`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            showToast('打包完成，已开始下载', 'success', 'zip-download');
          } finally {
            setZipProgress(null);
          }
          return;
        }
        const dlLimit = useTransferStore.getState().effective.downloadSpeedLimit;
        const url = await source.getDownloadUrl(nodeIds[0]);
        const sep = url.includes('?') ? '&' : '?';
        const finalUrl = dlLimit > 0 ? `${url}${sep}clientLimit=${dlLimit}` : url;
        const a = document.createElement('a');
        a.href = finalUrl;
        a.download = node?.name || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      } else {
        setZipProgress(0);
        try {
          showToast('正在打包下载，请稍候…', 'info', 'zip-download');
          const blob = await source.downloadZip(nodeIds, (loaded) => setZipProgress(loaded));
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'download.zip';
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
          showToast('打包完成，已开始下载', 'success', 'zip-download');
        } finally {
          setZipProgress(null);
        }
      }
    } catch {
      showToast('下载失败', 'error');
    }
  }, [files, source, showToast, setDownloadTarget, setZipProgress, setDownloadQueuedCount]);

  return { handleDownload };
}
