import { createContext, useContext, useState, useCallback, useRef, useEffect, type ReactNode } from 'react';
import api from '../lib/api';
import { isElectron } from '../lib/electron';
import { useTransferStore } from '../store/transfer';
import { useStorageStore } from '../store/storage';
import type { UploadTask, UploadTaskStatus, TransferTask } from '../types';
import UploadPanel from '../components/file/UploadPanel';

interface UploadContextValue {
  tasks: UploadTask[];
  addFiles: (files: File[], parentId: string, replaceFileId?: string, spaceId?: string) => void;
  addFilePaths: (filePaths: string[], parentId: string, replaceFileId?: string, _spaceId?: string) => void;
  removeTask: (id: string) => void;
  clearCompleted: () => void;
  panelOpen: boolean;
  setPanelOpen: (open: boolean) => void;
  refreshSignal: number;
}

const UploadContext = createContext<UploadContextValue | null>(null);

const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB


// SparkMD5 for MD5 calculation
import SparkMD5 from 'spark-md5';

async function calculateMd5(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const spark = new SparkMD5.ArrayBuffer();
    const reader = new FileReader();
    reader.onload = (e) => {
      spark.append(e.target?.result as ArrayBuffer);
      // Append file size so files with identical prefixes but different sizes do not collide
      const sizeBuf = new ArrayBuffer(8);
      new DataView(sizeBuf).setFloat64(0, file.size);
      spark.append(sizeBuf);
      resolve(spark.end());
    };
    reader.onerror = () => reject(new Error('Failed to read file for MD5'));
    // For large files, hash the first 2MB + file size for speed; otherwise hash the whole file
    if (file.size > 10 * 1024 * 1024) {
      const blob = file.slice(0, 2 * 1024 * 1024);
      reader.readAsArrayBuffer(blob);
    } else {
      reader.readAsArrayBuffer(file);
    }
  });
}

async function uploadChunkToS3(url: string, chunk: Blob): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    xhr.timeout = 120000;
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(`上传失败 (${xhr.status})`)));
    xhr.onerror = () => reject(new Error('上传网络错误，请检查存储服务 CORS 配置'));
    xhr.ontimeout = () => reject(new Error('上传超时'));
    xhr.send(chunk);
  });
}

export function UploadProvider({ children }: { children: ReactNode }) {
  const [tasks, setTasks] = useState<UploadTask[]>([]);
  const [panelOpen, setPanelOpen] = useState(false);
  const [refreshSignal, setRefreshSignal] = useState(0);
  const autoCollapseFired = useRef(false);

  const updateTask = useCallback((id: string, updates: Partial<UploadTask>) => {
    setTasks((prev) => prev.map((t) => (t.id === id ? { ...t, ...updates } : t)));
  }, []);

  // Auto-collapse panel 5s after all tasks finish (fires once per batch)
  useEffect(() => {
    if (tasks.length === 0) {
      autoCollapseFired.current = false;
      return;
    }
    const hasActive = tasks.some((t) => !['completed', 'instant', 'failed'].includes(t.status));
    if (hasActive) {
      autoCollapseFired.current = false; // Reset when new uploads start
    } else if (panelOpen && !autoCollapseFired.current) {
      autoCollapseFired.current = true;
      const timer = setTimeout(() => setPanelOpen(false), 5000);
      return () => clearTimeout(timer);
    }
  }, [tasks, panelOpen]);

  const addFiles = useCallback(async (files: File[], parentId: string, replaceFileId?: string, spaceId?: string) => {
    setPanelOpen(true);

    for (const file of files) {
      const taskId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      const task: UploadTask = {
        id: taskId,
        file,
        parentId,
        replaceFileId,
        status: 'hashing',
        progress: 0,
        fileSize: file.size,
        fileName: file.name,
      };
      setTasks((prev) => [...prev, task]);

      try {
        // Step 1: Calculate MD5
        const fileMd5 = await calculateMd5(file);
        updateTask(taskId, { fileMd5, status: 'pending', progress: 5 });

        // Step 2: Check instant upload（替换上传跳过秒传，始终生成新版本）
        if (!replaceFileId) {
          const checkRes = await api.post<{ instant?: boolean }>('/file/upload/check', {
            fileMd5,
            fileSize: file.size,
            fileName: file.name,
            parentId,
            ...(spaceId ? { spaceId } : {}),
          });

          if (checkRes?.instant) {
            updateTask(taskId, { status: 'instant', progress: 100 });
            setRefreshSignal((s) => s + 1);
            if (!spaceId) useStorageStore.getState().fetchStorage();
            continue;
          }
        }

        // Step 3: 分片上传（服务端门控限速，逐片申请URL）
        const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
        const initRes = (await api.post('/file/upload/init', {
          fileName: file.name,
          fileSize: file.size,
          fileMd5,
          totalChunks,
          chunkSize: CHUNK_SIZE,
          parentId,
          ...(replaceFileId ? { replaceFileId } : {}),
          ...(spaceId ? { spaceId } : {}),
        })) as { uploadId: string; s3UploadId: string };

        updateTask(taskId, {
          status: 'uploading',
          uploadId: initRes.uploadId,
          s3UploadId: initRes.s3UploadId,
          totalChunks,
          uploadedChunks: [],
          progress: 10,
        });

        const concurrency = 5;
        let uploadedCount = 0;
        const uploadChunk = async (index: number) => {
          const start = (index - 1) * CHUNK_SIZE;
          const end = Math.min(start + CHUNK_SIZE, file.size);
          const chunk = file.slice(start, end);

          // 逐片向服务端申请预签名URL（服务端令牌桶门控限速，url为空时等待重试）
          let url = '';
          while (!url) {
            const res = (await api.get('/file/upload/chunk-url', {
              params: { uploadId: initRes.uploadId, s3UploadId: initRes.s3UploadId, chunkIndex: index, clientLimit: useTransferStore.getState().effective.uploadSpeedLimit },
            })) as { url: string; retryAfterMs?: number };
            if (res?.url) {
              url = res.url;
            } else {
              await new Promise((r) => setTimeout(r, Math.min(res?.retryAfterMs || 500, 5000)));
            }
          }
          await uploadChunkToS3(url, chunk);
          // 确认分片上传完成，释放服务端限速配额（失败不阻断上传，令牌到期自动回收）
          try {
            await api.post('/file/upload/chunk-confirm', null, {
              params: { uploadId: initRes.uploadId, s3UploadId: initRes.s3UploadId, chunkIndex: index },
            });
          } catch {
            // ignore confirm error
          }
          uploadedCount++;
          setTasks((prev) => prev.map((t) => t.id !== taskId ? t : {
            ...t,
            progress: 10 + Math.floor((uploadedCount / totalChunks) * 80),
            uploadedChunks: [...(t.uploadedChunks || []), index],
          }));
        };

        const chunkIndexes = Array.from({ length: totalChunks }, (_, i) => i + 1);
        for (let i = 0; i < chunkIndexes.length; i += concurrency) {
          const batch = chunkIndexes.slice(i, i + concurrency);
          await Promise.all(batch.map(uploadChunk));
        }

        // Merge chunks
        updateTask(taskId, { status: 'merging', progress: 95 });
        await api.post('/file/upload/merge', {
          uploadId: initRes.uploadId,
          s3UploadId: initRes.s3UploadId,
        });
        updateTask(taskId, { status: 'completed', progress: 100 });
        setRefreshSignal((s) => s + 1);
        if (!spaceId) useStorageStore.getState().fetchStorage();
      } catch (err) {
        console.error('Upload failed:', err);
        updateTask(taskId, {
          status: 'failed',
          error: err instanceof Error ? err.message : '上传失败',
        });
      }
    }
  }, [updateTask]);

  // Electron 模式：通过 IPC 委托上传
  const addFilePaths = useCallback(async (filePaths: string[], parentId: string, replaceFileId?: string, _spaceId?: string) => {
    if (!isElectron()) return;
    setPanelOpen(true);

    for (const filePath of filePaths) {
      const fileName = filePath.split(/[/\\]/).pop() || filePath;
      const taskId = `e-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      const task: UploadTask = {
        id: taskId,
        file: null,
        parentId,
        replaceFileId,
        status: 'hashing',
        progress: 0,
        fileSize: 0,
        fileName,
      };
      setTasks((prev) => [...prev, task]);

      try {
        const electronTaskId = await window.electronAPI!.startUpload(filePath, parentId, replaceFileId);
        // 标记为 electron 管理的任务，后续进度由 IPC 更新
        updateTask(taskId, { status: 'uploading', electronTaskId: electronTaskId as string });
      } catch (err) {
        updateTask(taskId, {
          status: 'failed',
          error: err instanceof Error ? err.message : '上传失败',
        });
      }
    }
  }, [updateTask]);

  // Electron 模式：监听 IPC 任务更新
  useEffect(() => {
    if (!isElectron()) return;
    const unsubscribe = window.electronAPI!.onTaskUpdate((transferTask: TransferTask) => {
      setTasks((prev) => {
        // 找到对应的 electron 任务
        const idx = prev.findIndex((t) => t.electronTaskId === transferTask.id);
        if (idx === -1) return prev;

        const statusMap: Record<string, UploadTaskStatus> = {
          pending: 'pending',
          hashing: 'hashing',
          uploading: 'uploading',
          paused: 'paused',
          merging: 'merging',
          completed: 'completed',
          failed: 'failed',
        };

        const updated = [...prev];
        updated[idx] = {
          ...updated[idx],
          progress: transferTask.progress,
          fileSize: transferTask.fileSize,
          status: statusMap[transferTask.status] || 'uploading',
          error: transferTask.error || undefined,
        };
        return updated;
      });

      if (transferTask.status === 'completed') {
        setRefreshSignal((s) => s + 1);
      }
    });
    return unsubscribe;
  }, []);

  const removeTask = useCallback((id: string) => {
    setTasks((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const clearCompleted = useCallback(() => {
    setTasks((prev) => prev.filter((t) => t.status !== 'completed' && t.status !== 'instant'));
  }, []);

  return (
    <UploadContext.Provider value={{ tasks, addFiles, addFilePaths, removeTask, clearCompleted, panelOpen, setPanelOpen, refreshSignal }}>
      {children}
      <UploadPanel />
    </UploadContext.Provider>
  );
}

export function useUpload() {
  const ctx = useContext(UploadContext);
  if (!ctx) throw new Error('useUpload must be used within UploadProvider');
  return ctx;
}
