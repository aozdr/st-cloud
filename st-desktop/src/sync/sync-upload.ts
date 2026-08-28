import fs from 'fs';
import path from 'path';
import { apiClient } from '../api-client';
import { startUpload } from '../upload-manager';
import { getTask, insertSyncHistory, setBlockHashes, deleteBlockHashes, getSyncState, upsertSyncState } from '../database';
import { calculateSampledMd5, calculateFileMd5 } from '../utils/md5';
import { computeBackoffMs } from '../sync-retry';
import { calculateBlockHashes, readBlockData, BLOCK_SIZE } from '../utils/block-hash';
import { emitSyncEvent, syncLog, BLOCK_SYNC_THRESHOLD, type SyncEngineCtx } from './sync-shared';

/**
 * 同步引擎上传模块：全量上传 / 块级增量上传 / 失败退避记账。
 * 拆分自 sync-engine.ts，方法体逻辑保持不变（this -> ctx）。
 */

/**
 * 块级增量上传：大文件（>=8MB）修改后仅上传变化块。
 * 流程：计算块哈希 -> block-check 对比 -> 上传缺失块 -> block-upload 组装。
 * 失败自动回退全量上传。
 */
async function uploadFileBlockLevel(ctx: SyncEngineCtx, absPath: string, relPath: string, existingNodeId: string): Promise<boolean> {
  const stat = fs.statSync(absPath);
  const fileName = path.basename(absPath);

  try {
    // 1. 按 5MB 分块计算块哈希 + 全文件 MD5
    const blockHashes = await calculateBlockHashes(absPath);
    const fileMd5 = await calculateFileMd5(absPath);
    const totalBlocks = blockHashes.length;

    // 2. block-check：对比服务端块布局，初始化 multipart，返回可复用/缺失块
    syncLog('upload', `块检查: ${fileName} (${totalBlocks} 块)`);
    const checkRes = await apiClient.post('/sync/block-check', {
      // 雪花ID超出 JS 安全整数范围（2^53），必须按字符串传输，防止精度丢失查不到文件
      fileNodeId: existingNodeId,
      fileMd5,
      fileSize: stat.size,
      blockSize: BLOCK_SIZE,
      blocks: blockHashes.map((b) => ({ index: b.index, md5: b.md5, size: b.size })),
    });
    const checkData = checkRes.data?.data;
    if (!checkData) {
      throw new Error('block-check 响应异常: ' + (checkRes.data?.message || '未知错误'));
    }

    const reusableCount = checkData.reusableBlocks?.length ?? 0;
    const missingBlocks = checkData.missingBlocks ?? [];
    syncLog('upload', `块对比完成: ${fileName} 可复用 ${reusableCount} 块, 需上传 ${missingBlocks.length} 块`);

    // 3. 上传缺失块到预签名 URL
    for (const mb of missingBlocks) {
      const blockData = readBlockData(absPath, mb.blockIndex);
      let uploadError: Error | null = null;
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          const response = await fetch(mb.presignedUrl, {
            method: 'PUT',
            body: blockData,
            headers: { 'Content-Type': 'application/octet-stream' },
          });
          if (!response.ok) {
            throw new Error(`块 ${mb.blockIndex} 上传失败: ${response.status}`);
          }
          uploadError = null;
          break;
        } catch (err) {
          uploadError = err as Error;
          if (attempt < 3) {
            await new Promise((r) => setTimeout(r, 1000 * Math.pow(2, attempt - 1)));
          }
        }
      }
      if (uploadError) throw uploadError;
    }

    // 4. block-upload：复制可复用块 + 合并 multipart + 更新元数据
    syncLog('upload', `块组装: ${fileName}`);
    const uploadRes = await apiClient.post('/sync/block-upload', {
      // 同上：雪花ID按字符串传输
      fileNodeId: existingNodeId,
      s3UploadId: checkData.s3UploadId,
      storagePath: checkData.storagePath,
      fileMd5,
      fileSize: stat.size,
      blockSize: BLOCK_SIZE,
      totalBlocks,
      blocks: blockHashes.map((b) => ({ index: b.index, md5: b.md5, size: b.size })),
    });
    const uploadData = uploadRes.data?.data;
    if (!uploadData) {
      throw new Error('block-upload 响应异常: ' + (uploadRes.data?.message || '未知错误'));
    }

    // 5. 缓存块哈希 + 更新同步状态
    setBlockHashes(ctx.root.rootId, relPath, blockHashes.map((b) => ({
      blockIndex: b.index, blockMd5: b.md5, blockSize: b.size,
    })));
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'upload', fileName, relPath, status: 'success', detail: `块级上传(${reusableCount}块复用/${missingBlocks.length}块新增)` });
    upsertSyncState({ rootId: ctx.root.rootId,
      localPath: relPath,
      nodeId: existingNodeId,
      md5: fileMd5,
      size: stat.size,
      localMtime: stat.mtimeMs,
      status: 'synced',
      failCount: 0,
      failMtime: undefined,
      nextRetryAt: undefined,
    });
    syncLog('upload', `块级上传完成: ${fileName} (复用 ${reusableCount}/${totalBlocks} 块)`);
    return true;
  } catch (err) {
    syncLog('error', `块级上传失败: ${fileName} - ${String(err)}`);
    deleteBlockHashes(ctx.root.rootId, relPath);
    return false;
  }
}

/**
 * 上传文件：新建用 init+merge；已存在用 replaceFileId 覆盖（触发版本快照）
 */
export async function uploadFile(ctx: SyncEngineCtx, absPath: string, relPath: string, existingNodeId?: string): Promise<void> {
  const stat = fs.statSync(absPath);
  const fileName = path.basename(absPath);
  // 更新已有文件且大小 >=8MB 时优先走块级增量上传，失败回退全量
  if (existingNodeId && stat.size >= BLOCK_SYNC_THRESHOLD) {
    const blockSuccess = await uploadFileBlockLevel(ctx, absPath, relPath, existingNodeId);
    if (blockSuccess) return;
  }

  const replaceFileId = existingNodeId || undefined;

  syncLog(replaceFileId ? 'upload' : 'create', replaceFileId ? '更新文件: ' + fileName : '上传新文件: ' + fileName);
  const taskId = await startUpload(absPath, ctx.root.cloudFolderNodeId, replaceFileId);

  const result = await waitForTask(taskId);

  if (result !== 'completed') {
    // 记录失败并进入指数退避，避免反复触发形成重试风暴；原实现静默失败（无日志、无退避）
    const failedTask = getTask(taskId);
    const errorMsg = failedTask?.error ? String(failedTask.error) : result;
    recordUploadFailure(ctx, relPath, stat.mtimeMs);
    emitSyncEvent('upload_failed', { relPath, error: errorMsg });
    const waitSec = Math.ceil(computeBackoffMs(getSyncState(ctx.root.rootId, relPath)?.failCount ?? 1) / 1000);
    syncLog('error', `上传失败: ${fileName} - ${errorMsg}（将在 ${waitSec}s 后重试）`);
    return;
  }

  const task = getTask(taskId);
  const nodeId = task?.fileId ? String(task.fileId) : existingNodeId;

  const md5 = await calculateSampledMd5(absPath, stat.size).catch(() => undefined);
  syncLog('upload', '上传完成: ' + fileName);
  insertSyncHistory({ rootId: ctx.root.rootId, action: 'upload', fileName, relPath, status: 'success' });
  upsertSyncState({ rootId: ctx.root.rootId,
    localPath: relPath,
    nodeId,
    md5,
    size: stat.size,
    localMtime: stat.mtimeMs,
    status: 'synced',
    failCount: 0,
    failMtime: undefined,
    nextRetryAt: undefined,
  });
}

/**
 * 上传失败记账：失败次数 +1、记录失败时的本地 mtime、按指数退避计算下次重试时间。
 * mtime 记录用于区分"同一文件反复失败"与"用户再次修改"（后者立即重试）。
 */
function recordUploadFailure(ctx: SyncEngineCtx, relPath: string, mtimeMs: number): void {
  const prev = getSyncState(ctx.root.rootId, relPath);
  const failCount = (prev?.failCount ?? 0) + 1;
  upsertSyncState({ rootId: ctx.root.rootId,
    localPath: relPath,
    nodeId: prev?.nodeId,
    md5: prev?.md5,
    size: prev?.size,
    localMtime: prev?.localMtime,
    cloudMtime: prev?.cloudMtime,
    status: prev?.status ?? 'error',
    failCount,
    failMtime: mtimeMs,
    nextRetryAt: Date.now() + computeBackoffMs(failCount),
  });
}

function waitForTask(taskId: string, timeoutMs = 600_000): Promise<string> {
  const start = Date.now();
  return new Promise((resolve) => {
    const check = () => {
      const t = getTask(taskId);
      if (t && (t.status === 'completed' || t.status === 'failed')) {
        resolve(t.status);
        return;
      }
      if (Date.now() - start > timeoutMs) {
        resolve('timeout');
        return;
      }
      setTimeout(check, 1000);
    };
    check();
  });
}
