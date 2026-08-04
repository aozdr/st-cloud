import crypto from 'crypto';
import fs from 'fs';
import https from 'https';
import http from 'http';
import path from 'path';
import { URL } from 'url';
import { BrowserWindow } from 'electron';
import { apiClient, getToken } from './api-client';
import { getTransferSettings } from './transfer-settings';
import { createTask, updateTask, getTask, deleteTask, getPendingTasks } from './database';
import {
  getDownloadTempPath,
  getDownloadedBytes,
  deleteDownloadTempFile,
  finalizeDownloadFile,
  getUniqueFilePath,
} from './utils/file-utils';
import { scheduleTask, releaseTask, cancelPendingTask } from './task-scheduler';
import type { TransferTask } from './types';

const activeDownloads = new Map<string, { paused: boolean; cancelled: boolean; request?: http.ClientRequest }>();

function emitTaskUpdate(task: TransferTask): void {
  for (const win of BrowserWindow.getAllWindows()) {
    win.webContents.send('task:update', task);
  }
}

function makeTaskId(): string {
  return crypto.randomUUID();
}

export async function startDownload(
  nodeId: string,
  fileName: string,
  fileSize: number,
  savePath: string
): Promise<string> {
  // 检查本地重名文件，按 Windows 规则自动重命名
  const uniquePath = getUniqueFilePath(savePath);
  const finalFileName = path.basename(uniquePath);

  const taskId = makeTaskId();

  const task: TransferTask = {
    id: taskId,
    type: 'download',
    status: 'downloading',
    fileName: finalFileName,
    fileSize,
    transferredBytes: 0,
    speed: 0,
    progress: 0,
    error: null,
    createdAt: new Date().toISOString(),
    nodeId,
    savePath: uniquePath,
  };

  createTask(task);
  emitTaskUpdate(task);

  // 通过调度器异步执行下载流程（受最大并行任务数控制）
  scheduleTask(taskId, () => doDownload(taskId).catch((err) => {
    const t = getTask(taskId);
    if (t && t.status !== 'cancelled' && t.status !== 'paused') {
      updateTask(taskId, { status: 'failed', error: String(err) });
      emitTaskUpdate(getTask(taskId)!);
    }
  }));

  return taskId;
}

async function doDownload(taskId: string): Promise<void> {
  const state = { paused: false, cancelled: false, request: undefined as http.ClientRequest | undefined };
  activeDownloads.set(taskId, state);

  try {
    const task = getTask(taskId)!;

    // 服务端限速流式下载（不再使用预签名直链，避免绕过限速）
    const token = getToken();
    const dlLimit = getTransferSettings().downloadSpeedLimit;
    const streamUrl = `${apiClient.defaults.baseURL}/file/${task.nodeId}/stream?token=${encodeURIComponent(token || '')}${dlLimit > 0 ? `&clientLimit=${dlLimit}` : ''}`;

    // 检查已有临时文件
    const existingBytes = getDownloadedBytes(taskId);
    const tempPath = getDownloadTempPath(taskId);

    await new Promise<void>((resolve, reject) => {
      const url = new URL(streamUrl);
      const lib = url.protocol === 'https:' ? https : http;

      const headers: Record<string, string> = {};
      if (existingBytes > 0) {
        headers['Range'] = `bytes=${existingBytes}-`;
      }

      const req = lib.get(url, { headers }, (response) => {
        // 200 = 全量下载, 206 = Range 续传
        if (response.statusCode !== 200 && response.statusCode !== 206) {
          reject(new Error(`下载失败: HTTP ${response.statusCode}`));
          return;
        }

        // 追加写入临时文件
        const writeStream = fs.createWriteStream(tempPath, {
          flags: existingBytes > 0 ? 'a' : 'w',
        });

        let transferred = existingBytes;
        let lastUpdate = Date.now();
        let lastBytes = transferred;

        response.on('data', (chunk: Buffer) => {
          if (state.cancelled) {
            req.destroy();
            writeStream.destroy();
            resolve();
            return;
          }
          if (state.paused) {
            req.destroy();
            writeStream.destroy();
            resolve();
            return;
          }

          transferred += chunk.length;
          writeStream.write(chunk);

          // 每秒更新一次进度
          const now = Date.now();
          if (now - lastUpdate >= 1000) {
            const speed = ((transferred - lastBytes) / (now - lastUpdate)) * 1000;
            const progress = Math.min(100, Math.round((transferred / task.fileSize) * 100));
            updateTask(taskId, { transferredBytes: transferred, progress, speed: Math.round(speed) });
            emitTaskUpdate(getTask(taskId)!);
            lastUpdate = now;
            lastBytes = transferred;
          }
        });

        writeStream.on('error', reject);
        response.on('end', () => {
          writeStream.end(() => {
            const finalTransferred = existingBytes + (response.headers['content-length']
              ? parseInt(response.headers['content-length'])
              : transferred - existingBytes);

            if (finalTransferred >= task.fileSize || (!state.paused && !state.cancelled)) {
              // 下载完成
              finalizeDownloadFile(taskId, task.savePath!);
              updateTask(taskId, { status: 'completed', progress: 100, transferredBytes: task.fileSize, speed: 0 });
              emitTaskUpdate(getTask(taskId)!);
            }
            resolve();
          });
        });

        response.on('error', (err) => {
          writeStream.destroy();
          if (state.paused || state.cancelled) {
            resolve();
            return;
          }
          reject(err);
        });
      });

      req.on('error', (err) => {
        if (state.paused || state.cancelled) {
          resolve();
          return;
        }
        reject(err);
      });
      state.request = req;
    });
  } finally {
    activeDownloads.delete(taskId);
  }
}

export function pauseDownload(taskId: string): void {
  const state = activeDownloads.get(taskId);
  if (state) {
    state.paused = true;
    if (state.request) {
      state.request.destroy();
    }
  }
  updateTask(taskId, { status: 'paused', speed: 0 });
  const task = getTask(taskId);
  if (task) emitTaskUpdate(task);
  // 释放任务槽位，让等待中的任务可以执行
  releaseTask(taskId);
}

export async function resumeDownload(taskId: string): Promise<void> {
  const task = getTask(taskId);
  if (!task) return;

  updateTask(taskId, { status: 'downloading' });
  emitTaskUpdate(getTask(taskId)!);

  // 通过调度器执行恢复下载（受最大并行任务数控制）
  scheduleTask(taskId, () => doDownload(taskId).catch((err) => {
    const t = getTask(taskId);
    if (t && t.status !== 'cancelled' && t.status !== 'paused') {
      updateTask(taskId, { status: 'failed', error: String(err) });
      emitTaskUpdate(getTask(taskId)!);
    }
  }));
}

export async function cancelDownload(taskId: string): Promise<void> {
  const state = activeDownloads.get(taskId);
  if (state) {
    state.cancelled = true;
    if (state.request) {
      state.request.destroy();
    }
  }

  // 从等待队列中移除（如果在排队中）
  cancelPendingTask(taskId);
  // 释放活动槽位（如果正在执行）
  releaseTask(taskId);

  deleteDownloadTempFile(taskId);
  deleteTask(taskId);
  activeDownloads.delete(taskId);
}

/**
 * 应用启动时恢复未完成的下载任务
 */
export async function resumePendingDownloads(): Promise<void> {
  const pending = getPendingTasks().filter((t) => t.type === 'download' && t.status === 'downloading');

  for (const task of pending) {
    // 标记为 paused，等用户手动恢复
    updateTask(task.id, { status: 'paused', speed: 0 });
    emitTaskUpdate(getTask(task.id)!);
  }
}
