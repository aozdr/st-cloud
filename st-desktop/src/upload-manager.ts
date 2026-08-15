import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { BrowserWindow } from 'electron';
import { apiClient } from './api-client';
import { getTransferSettings } from './transfer-settings';
import { createTask, updateTask, getTask, deleteTask, getPendingTasks } from './database';
import { calculateSampledMd5 } from './utils/md5';
import { readChunk, getTotalChunks, CHUNK_SIZE, CONCURRENCY } from './utils/file-utils';
import { scheduleTask, releaseTask, cancelPendingTask } from './task-scheduler';
import type { TransferTask, UploadCheckResponse, UploadInitResponse, UploadStatusResponse } from './types';

const activeUploads = new Map<string, { paused: boolean; cancelled: boolean }>();

/** 替换上传元数据（仅初始 doUpload 的 init 阶段需要，resume 不需要） */
const uploadMeta = new Map<string, { replaceFileId?: string }>();

function emitTaskUpdate(task: TransferTask): void {
  const windows = BrowserWindow.getAllWindows();
  for (const win of windows) {
    win.webContents.send('task:update', task);
  }
}

function makeTaskId(): string {
  return crypto.randomUUID();
}

export async function startUpload(filePath: string, parentId: string, replaceFileId?: string): Promise<string> {
  const fileName = path.basename(filePath);
  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const taskId = makeTaskId();

  const task: TransferTask = {
    id: taskId,
    type: 'upload',
    status: 'hashing',
    fileName,
    fileSize,
    transferredBytes: 0,
    speed: 0,
    progress: 0,
    error: null,
    createdAt: new Date().toISOString(),
    filePath,
    parentId,
  };

  if (replaceFileId) {
    uploadMeta.set(taskId, { replaceFileId });
  }
  createTask(task);
  emitTaskUpdate(task);

  // 通过调度器异步执行上传流程（受最大并行任务数控制）
  scheduleTask(taskId, () => doUpload(taskId).catch((err) => {
    const t = getTask(taskId);
    if (t && t.status !== 'cancelled' && t.status !== 'paused') {
      updateTask(taskId, { status: 'failed', error: String(err) });
      const updated = getTask(taskId)!;
      emitTaskUpdate(updated);
    }
  }));

  return taskId;
}

async function doUpload(taskId: string): Promise<void> {
  const state = { paused: false, cancelled: false };
  activeUploads.set(taskId, state);

  try {
    const task = getTask(taskId)!;

    // 1. 计算 MD5
    const md5 = await calculateSampledMd5(task.filePath!, task.fileSize);

    if (state.cancelled) return;

    const meta = uploadMeta.get(taskId);
    const replaceFileId = meta?.replaceFileId;

    // 2. 秒传检查（替换上传跳过秒传，始终生成新版本）
    if (!replaceFileId) {
      const checkRes = await apiClient.post('/file/upload/check', {
        fileMd5: md5,
        fileName: task.fileName,
        fileSize: task.fileSize,
        parentId: task.parentId,
      });
      const checkData: UploadCheckResponse = checkRes.data?.data;

      if (checkData?.instant) {
        updateTask(taskId, { status: 'completed', progress: 100, transferredBytes: task.fileSize });
        emitTaskUpdate(getTask(taskId)!);
        return;
      }
    }

    if (state.cancelled) return;

    // 3. 初始化分片上传
    const totalChunks = getTotalChunks(task.fileSize);
    const clientUploadLimit = getTransferSettings().uploadSpeedLimit;
    const initRes = await apiClient.post('/file/upload/init', {
      fileMd5: md5,
      fileName: task.fileName,
      fileSize: task.fileSize,
      parentId: task.parentId,
      totalChunks,
      chunkSize: CHUNK_SIZE,
      ...(replaceFileId ? { replaceFileId: replaceFileId } : {}),
      ...(clientUploadLimit > 0 ? { clientLimit: clientUploadLimit } : {}),
    });
    const initData: UploadInitResponse = initRes.data?.data;
    if (!initData) {
      // 后端业务失败（如替换文件 ID 不存在）时 data 为空，这里给出明确报错而不是 TypeError
      throw new Error('上传初始化失败: ' + (initRes.data?.message || '未知错误'));
    }
    uploadMeta.delete(taskId);

    const transferMode = initData.transferMode || 'direct';
    updateTask(taskId, {
      status: 'uploading',
      uploadId: initData.uploadId,
      s3UploadId: initData.s3UploadId,
      fileId: initData.fileId,
      totalChunks,
      uploadedChunks: [],
      transferMode,
      relayLimitKb: initData.relayRateKb ?? clientUploadLimit,
    });
    emitTaskUpdate(getTask(taskId)!);

    if (transferMode === 'relay' && initData.relayChunkSize) {
      // 中转模式：限速 < 分片下限时走服务端中转，小块顺序 POST + pacing 节流
      updateTask(taskId, { status: 'uploading', transferredBytes: 0, speed: 0 });
      emitTaskUpdate(getTask(taskId)!);
      await relayUploadChunks(taskId, task.filePath!, initData.uploadId, initData.s3UploadId,
        initData.relayChunkSize, task.fileSize, state);
      if (state.cancelled) return;
      if (state.paused) return;
      // 中转完成：调用 relay-finalize
      updateTask(taskId, { status: 'merging', speed: 0 });
      emitTaskUpdate(getTask(taskId)!);
      const finalizeRes = await apiClient.post('/file/upload/relay-finalize', null, {
        params: { uploadId: initData.uploadId, s3UploadId: initData.s3UploadId },
      });
      if (finalizeRes.data?.code === 200 || finalizeRes.data?.code === 0) {
        updateTask(taskId, { status: 'completed', progress: 100, speed: 0 });
        emitTaskUpdate(getTask(taskId)!);
      } else {
        throw new Error(finalizeRes.data?.message || '合并失败');
      }
    } else {
    // 4. 分片上传（逐片向服务端申请URL，服务端门控限速）
    let uploadedChunks: number[] = [];

    await uploadChunks(taskId, task.filePath!, initData.uploadId, initData.s3UploadId, uploadedChunks, totalChunks, state);

    if (state.cancelled) return;
    if (state.paused) return; // 用户暂停，释放槽位，不合并

    // 5. 合并
    const currentTask = getTask(taskId)!;
    updateTask(taskId, { status: 'merging', speed: 0 });
    emitTaskUpdate(getTask(taskId)!);

    const mergeRes = await apiClient.post('/file/upload/merge', {
      uploadId: currentTask.uploadId,
      s3UploadId: currentTask.s3UploadId,
      fileId: currentTask.fileId,
    });

    if (mergeRes.data?.code === 200 || mergeRes.data?.code === 0) {
      updateTask(taskId, { status: 'completed', progress: 100, speed: 0 });
      emitTaskUpdate(getTask(taskId)!);
    } else {
      throw new Error(mergeRes.data?.message || '合并失败');
    }
    } // end else (direct mode)
  } finally {
    activeUploads.delete(taskId);
  }
}

// 中转模式上传：按 relayChunkSize 切小块顺序 POST 到服务端，服务端 pacing 节流接收
async function relayUploadChunks(
  taskId: string,
  filePath: string,
  uploadId: string,
  s3UploadId: string,
  relayChunkSize: number,
  fileSize: number,
  state: { paused: boolean; cancelled: boolean }
): Promise<void> {
  const totalRelayChunks = Math.ceil(fileSize / relayChunkSize);
  let lastUpdate = Date.now();
  let lastBytes = 0;

  for (let seq = 1; seq <= totalRelayChunks; seq++) {
    if (state.cancelled) return;
    if (state.paused) return;

    const start = (seq - 1) * relayChunkSize;
    const end = Math.min(start + relayChunkSize, fileSize);
    const chunkSize = end - start;
    const chunkData = readChunk(filePath, seq, relayChunkSize);

    const MAX_RETRIES = 3;
    let uploadError: Error | null = null;
    for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      if (state.cancelled) return;
      try {
        await apiClient.post('/file/upload/relay-chunk', chunkData, {
          params: { uploadId, s3UploadId, seq },
          headers: { 'Content-Type': 'application/octet-stream' },
          timeout: 300000,
        });
        uploadError = null;
        break;
      } catch (err) {
        uploadError = err as Error;
        if (attempt < MAX_RETRIES) {
          const backoff = Math.min(1000 * Math.pow(2, attempt - 1), 8000);
          await new Promise((r) => setTimeout(r, backoff));
        }
      }
    }
    if (uploadError) throw uploadError;

    const transferred = Math.min(seq * relayChunkSize, fileSize);
    const progress = Math.min(100, Math.round((transferred / fileSize) * 100));
    const now = Date.now();
    if (now - lastUpdate >= 1000) {
      const speed = Math.round(((transferred - lastBytes) / (now - lastUpdate)) * 1000);
      updateTask(taskId, { transferredBytes: transferred, progress, speed });
      lastUpdate = now;
      lastBytes = transferred;
    } else {
      updateTask(taskId, { transferredBytes: transferred, progress });
    }
    emitTaskUpdate(getTask(taskId)!);
  }
}

async function uploadChunks(
  taskId: string,
  filePath: string,
  uploadId: string,
  s3UploadId: string,
  uploadedChunks: number[],
  totalChunks: number,
  state: { paused: boolean; cancelled: boolean }
): Promise<void> {
  // 构建待上传分片队列（索引从1开始）
  const uploadedSet = new Set(uploadedChunks);
  const pendingChunks: number[] = [];
  for (let i = 1; i <= totalChunks; i++) {
    if (!uploadedSet.has(i)) {
      pendingChunks.push(i);
    }
  }

  // 并发上传池
  let index = 0;
  const task = getTask(taskId)!;

  // 速度计算共享状态
  let lastUpdate = Date.now();
  let lastBytes = uploadedChunks.length * CHUNK_SIZE;

  const uploadOne = async (): Promise<void> => {
    while (index < pendingChunks.length) {
      if (state.cancelled) return;
      if (state.paused) return; // 暂停时退出 worker，释放任务槽位

      const chunkIndex = pendingChunks[index++];

      // 逐片向服务端申请预签名URL（服务端令牌桶门控限速，url为空时等待重试）
      let url = '';
      while (!url) {
        if (state.cancelled) return;
        if (state.paused) return;
        const res = await apiClient.get('/file/upload/chunk-url', {
          params: { uploadId, s3UploadId, chunkIndex, clientLimit: getTransferSettings().uploadSpeedLimit },
        });
        const data = res.data?.data;
        if (data?.url) {
          url = data.url;
        } else {
          await new Promise((r) => setTimeout(r, Math.min(data?.retryAfterMs || 500, 5000)));
        }
      }

      const chunkData = readChunk(filePath, chunkIndex, CHUNK_SIZE);

      if (state.cancelled) return;
      if (state.paused) return;

      // 分片上传重试：最多 3 次，指数退避（1s → 2s → 4s）
      const MAX_RETRIES = 3;
      let uploadError: Error | null = null;
      for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        if (state.cancelled) return;
        try {
          const response = await fetch(url, {
            method: 'PUT',
            body: chunkData,
            headers: { 'Content-Type': 'application/octet-stream' },
          });
          if (!response.ok) {
            throw new Error(`分片 ${chunkIndex} 上传失败: ${response.status}`);
          }
          uploadError = null;
          break;
        } catch (err) {
          uploadError = err as Error;
          if (attempt < MAX_RETRIES) {
            const backoff = Math.min(1000 * Math.pow(2, attempt - 1), 8000);
            await new Promise((r) => setTimeout(r, backoff));
          }
        }
      }
      if (uploadError) throw uploadError;

      // 确认分片上传完成，释放服务端限速配额（失败不阻断上传，令牌到期自动回收）
      try {
        await apiClient.post('/file/upload/chunk-confirm', null, {
          params: { uploadId, s3UploadId, chunkIndex },
        });
      } catch {
        // ignore confirm error
      }

      uploadedChunks.push(chunkIndex);
      const transferred = uploadedChunks.length * CHUNK_SIZE;
      const progress = Math.min(100, Math.round((transferred / task.fileSize) * 100));

      // 计算速度（每秒更新一次）
      const now = Date.now();
      if (now - lastUpdate >= 1000) {
        const speed = Math.round(((transferred - lastBytes) / (now - lastUpdate)) * 1000);
        updateTask(taskId, {
          uploadedChunks: [...uploadedChunks],
          transferredBytes: Math.min(transferred, task.fileSize),
          progress,
          speed,
        });
        lastUpdate = now;
        lastBytes = transferred;
      } else {
        updateTask(taskId, {
          uploadedChunks: [...uploadedChunks],
          transferredBytes: Math.min(transferred, task.fileSize),
          progress,
        });
      }
      emitTaskUpdate(getTask(taskId)!);
    }
  };

  const workers: Promise<void>[] = [];
  for (let i = 0; i < CONCURRENCY; i++) {
    workers.push(uploadOne());
  }
  await Promise.all(workers);
}

export function pauseUpload(taskId: string): void {
  const state = activeUploads.get(taskId);
  if (state) {
    state.paused = true;
  }
  updateTask(taskId, { status: 'paused', speed: 0 });
  const task = getTask(taskId);
  if (task) emitTaskUpdate(task);
  // 释放任务槽位，让等待中的任务可以执行
  releaseTask(taskId);
}

export async function resumeUpload(taskId: string): Promise<void> {
  const task = getTask(taskId);
  if (!task) return;

  updateTask(taskId, { status: 'uploading' });
  emitTaskUpdate(getTask(taskId)!);

  // 通过调度器执行恢复上传（受最大并行任务数控制）
  scheduleTask(taskId, () => doResumeUpload(taskId).catch((err) => {
    const t = getTask(taskId);
    if (t && t.status !== 'cancelled' && t.status !== 'paused') {
      updateTask(taskId, { status: 'failed', error: String(err) });
      emitTaskUpdate(getTask(taskId)!);
    }
  }));
}

async function doResumeUpload(taskId: string): Promise<void> {
  const state = { paused: false, cancelled: false };
  activeUploads.set(taskId, state);

  try {
    const task = getTask(taskId)!;
    // 如果在排队期间被暂停或取消，直接返回
    if (task.status === 'paused' || task.status === 'cancelled') return;

    // 恢复上传：查询后端已上传分片 + 获取新鲜预签名 URL
    const statusRes = await apiClient.get('/file/upload/status', {
      params: { uploadId: task.uploadId, s3UploadId: task.s3UploadId },
    });
    const statusData: UploadStatusResponse = statusRes.data?.data;

    const uploadedChunks = statusData.uploadedChunkIndexes || [];

    const totalChunks = task.totalChunks || getTotalChunks(task.fileSize);

    // 更新本地状态
    const transferred = uploadedChunks.length * CHUNK_SIZE;
    const progress = Math.min(100, Math.round((transferred / task.fileSize) * 100));
    updateTask(taskId, { uploadedChunks, transferredBytes: Math.min(transferred, task.fileSize), progress });
    emitTaskUpdate(getTask(taskId)!);

    // 继续上传（逐片向服务端申请URL，服务端门控限速）
    await uploadChunks(taskId, task.filePath!, task.uploadId!, task.s3UploadId!, uploadedChunks, totalChunks, state);

    if (state.cancelled) return;
    if (state.paused) return; // 用户暂停了，不合并

    // 合并
    const currentTask = getTask(taskId)!;

    updateTask(taskId, { status: 'merging', speed: 0 });
    emitTaskUpdate(getTask(taskId)!);

    const mergeRes = await apiClient.post('/file/upload/merge', {
      uploadId: currentTask.uploadId,
      s3UploadId: currentTask.s3UploadId,
      fileId: currentTask.fileId,
    });

    if (mergeRes.data?.code === 200 || mergeRes.data?.code === 0) {
      updateTask(taskId, { status: 'completed', progress: 100, speed: 0 });
      emitTaskUpdate(getTask(taskId)!);
    }
  } finally {
    activeUploads.delete(taskId);
  }
}

export async function cancelUpload(taskId: string): Promise<void> {
  const state = activeUploads.get(taskId);
  if (state) {
    state.cancelled = true;
  }

  // 从等待队列中移除（如果在排队中）
  cancelPendingTask(taskId);
  // 释放活动槽位（如果正在执行）
  releaseTask(taskId);

  const task = getTask(taskId);
  if (task?.uploadId && task?.s3UploadId && task?.fileId) {
    try {
      await apiClient.delete('/file/upload/abort', {
        params: { uploadId: task.uploadId, s3UploadId: task.s3UploadId, fileId: task.fileId },
      });
    } catch {
      // ignore abort errors
    }
  }

  deleteTask(taskId);
  activeUploads.delete(taskId);
}

/**
 * 应用启动时恢复未完成的上传任务
 */
export async function resumePendingUploads(): Promise<void> {
  const pending = getPendingTasks().filter((t) => t.type === 'upload' && t.status === 'uploading');

  for (const task of pending) {
    // 标记为 paused，等用户手动恢复
    updateTask(task.id, { status: 'paused' });
    emitTaskUpdate(getTask(task.id)!);
  }
}
