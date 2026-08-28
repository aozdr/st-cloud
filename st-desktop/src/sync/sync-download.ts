import fs from 'fs';
import os from 'os';
import path from 'path';
import { apiClient } from '../api-client';
import { getSyncState, upsertSyncState, insertSyncHistory } from '../database';
import {
  uniqueConflictName,
  conflictRelPath,
} from '../sync-utils';
import { withRetry, syncLog, emitSyncEvent, type SyncEngineCtx, type DeltaItem } from './sync-shared';

/**
 * 同步引擎下载模块：云端变更下载 / 冲突处理（保留两份等四种策略）。
 * 拆分自 sync-engine.ts，方法体逻辑保持不变（this -> ctx）。
 */

export async function downloadFile(ctx: SyncEngineCtx, item: DeltaItem, absPath: string, relPath: string): Promise<void> {
  syncLog('download', '下载文件: ' + path.basename(relPath));
  try {
    const dir = path.dirname(absPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

    const res = await withRetry(
      () => apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' }),
      '下载文件: ' + path.basename(relPath),
    );
    const ws = fs.createWriteStream(absPath);
    await new Promise<void>((resolve, reject) => {
      res.data.pipe(ws);
      ws.on('finish', resolve);
      ws.on('error', reject);
    });

    const stat = fs.statSync(absPath);
    ctx.markEngineWritten(relPath);
    syncLog('download', '下载完成: ' + path.basename(relPath));
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'success' });
    upsertSyncState({ rootId: ctx.root.rootId,
      localPath: relPath,
      nodeId: item.nodeId,
      md5: item.md5 ?? undefined,
      size: stat.size,
      localMtime: stat.mtimeMs,
      cloudMtime: item.updatedAt,
      status: 'synced',
    });
  } catch (err) {
    console.error('[sync] download failed:', relPath, err);
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'error', detail: String(err) });
    emitSyncEvent('download_failed', { relPath, error: String(err) });
  }
}

/**
 * 冲突处理：保留两份
 * - 云端版下载为 "文件名 (冲突-时间戳).ext"（本地）
 * - 本地版上传为 "文件名 (本地-时间戳).ext"（云端，新建节点）
 */
export async function handleConflict(ctx: SyncEngineCtx, absPath: string, relPath: string, item: DeltaItem): Promise<void> {
  // 根据同步根的冲突策略决定解决方式
  const strategy = ctx.conflictStrategy || 'keep_both';
  syncLog('conflict', '文件冲突: ' + path.basename(relPath) + ' (策略: ' + strategy + ')');

  if (strategy === 'server_wins') {
    // 服务端为准：下载云端版覆盖本地
    await downloadFile(ctx, item, absPath, relPath);
    return;
  }

  if (strategy === 'local_wins') {
    // 本地为准：上传本地版覆盖云端
    const state = getSyncState(ctx.root.rootId, relPath);
    await ctx.uploadFile(absPath, relPath, state?.nodeId);
    return;
  }

  if (strategy === 'latest_wins') {
    // 对比本地修改时间与云端更新时间，保留较新版本
    const localStat = fs.statSync(absPath);
    const localMtime = localStat.mtimeMs;
    const cloudTime = new Date(item.updatedAt).getTime();
    if (localMtime >= cloudTime) {
      const state = getSyncState(ctx.root.rootId, relPath);
      await ctx.uploadFile(absPath, relPath, state?.nodeId);
    } else {
      await downloadFile(ctx, item, absPath, relPath);
    }
    return;
  }

  // keep_both（默认）：保留两份副本
  // 1) 云端版下载为本地 "xxx (冲突-ts).ext"，并立即登记 sync_state，
  //    防止监听器把它当“本地新建”回流上传（旧实现死循环根因之一）。
  const conflictLocal = uniqueConflictName(absPath, '冲突', (p) => fs.existsSync(p));
  let cloudCopyOk = false;
  try {
    const res = await apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' });
    const ws = fs.createWriteStream(conflictLocal);
    await new Promise<void>((resolve, reject) => {
      res.data.pipe(ws);
      ws.on('finish', resolve);
      ws.on('error', reject);
    });
    cloudCopyOk = true;
    const cStat = fs.statSync(conflictLocal);
    const cRel = conflictRelPath(relPath, conflictLocal, ctx.root.localPath);
    ctx.markEngineWritten(cRel);
    upsertSyncState({ rootId: ctx.root.rootId,
      localPath: cRel,
      nodeId: item.nodeId,
      md5: item.md5 ?? undefined,
      size: cStat.size,
      localMtime: cStat.mtimeMs,
      cloudMtime: item.updatedAt,
      status: 'conflict',
    });
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'download', fileName: path.basename(conflictLocal), relPath: cRel, status: 'success', detail: '冲突副本(云端版)' });
  } catch (err) {
    console.error('[sync] conflict download failed:', err);
  }

  // 2) 本地版上传为云端 "xxx (本地-ts).ext"：临时文件放系统临时目录，
  //    不再在同步目录内创建/删除临时文件（旧实现触发 unlink -> 反向删云端 的循环）。
  const conflictCloudName = uniqueConflictName(path.basename(absPath), '本地', () => false);
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'st-sync-conflict-'));
  let localCopyOk = false;
  try {
    const tempPath = path.join(tmpDir, conflictCloudName);
    fs.copyFileSync(absPath, tempPath);
    await ctx.uploadFile(tempPath, '/' + path.basename(tempPath));
    localCopyOk = true;
  } catch (err) {
    console.error('[sync] conflict local copy upload failed:', err);
  } finally {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ignore */ }
  }

  // 3) 原文件状态：保留本地内容与 mtime（合并写入不擦 local_mtime），
  //    云端节点与 md5 记录为云端版本，避免下一轮扫描再次判定“本地已修改”反复上传。
  upsertSyncState({ rootId: ctx.root.rootId, localPath: relPath, nodeId: item.nodeId, md5: item.md5 ?? undefined, status: 'conflict' });
  insertSyncHistory({ rootId: ctx.root.rootId, action: 'conflict', fileName: path.basename(relPath), relPath, status: 'success', detail: strategy + (cloudCopyOk ? '' : '(云端副本下载失败)') + (localCopyOk ? '' : '(本地副本上传失败)') });
  emitSyncEvent('conflict', { relPath, cloudCopy: path.basename(conflictLocal) });
}
