import fs from 'fs';
import os from 'os';
import path from 'path';
import crypto from 'crypto';
import { apiClient } from '../api-client';
import { getSyncState, getAllSyncStates, upsertSyncState, insertSyncHistory } from '../database';
import { calculateFileMd5 } from '../utils/md5';
import {
  uniqueConflictName,
  conflictRelPath,
  isConflictCopyName,
} from '../sync-utils';
import { withRetry, syncLog, emitSyncEvent, type SyncEngineCtx, type DeltaItem } from './sync-shared';

/**
 * 同步引擎下载模块：云端变更下载 / 冲突处理（保留两份等四种策略）。
 * 拆分自 sync-engine.ts，方法体逻辑保持不变（this -> ctx）。
 */

export async function downloadFile(ctx: SyncEngineCtx, item: DeltaItem, absPath: string, relPath: string,
                                   stateStatus: 'synced' | 'conflict' = 'synced'): Promise<void> {
  syncLog('download', '下载文件: ' + path.basename(relPath));
  const temporaryPath = path.join(path.dirname(absPath), `.st-sync-${crypto.randomUUID()}.tmp`);
  try {
    const dir = path.dirname(absPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

    const res = await withRetry(
      () => apiClient.get(`/file/${item.nodeId}/stream`, { responseType: 'stream' }),
      '下载文件: ' + path.basename(relPath),
    );
    const ws = fs.createWriteStream(temporaryPath, { flags: 'wx' });
    await new Promise<void>((resolve, reject) => {
      res.data.pipe(ws);
      ws.on('finish', resolve);
      ws.on('error', reject);
      res.data.on('error', reject);
    });

    const downloadedStat = fs.statSync(temporaryPath);
    if (item.size != null && downloadedStat.size !== item.size) throw new Error('下载长度不匹配');
    if (item.md5 && (await calculateFileMd5(temporaryPath)).toLowerCase() !== item.md5.toLowerCase()) {
      throw new Error('下载校验失败');
    }
    ctx.markEngineWritten(relPath);
    fs.renameSync(temporaryPath, absPath);
    const stat = fs.statSync(absPath);
    syncLog('download', '下载完成: ' + path.basename(relPath));
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'success' });
    upsertSyncState({ rootId: ctx.root.rootId,
      localPath: relPath,
      nodeId: item.nodeId,
      md5: item.md5 ?? undefined,
      size: stat.size,
      localMtime: stat.mtimeMs,
      cloudMtime: item.updatedAt,
      status: stateStatus,
    });
  } catch (err) {
    try { fs.unlinkSync(temporaryPath); } catch { /* 临时文件不存在 */ }
    console.error('[sync] download failed:', relPath, err);
    insertSyncHistory({ rootId: ctx.root.rootId, action: 'download', fileName: path.basename(relPath), relPath, status: 'error', detail: String(err) });
    emitSyncEvent('download_failed', { relPath, error: String(err) });
    throw err;
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
  // 成功后确认的是本次冲突开始时的本地版本；异步期间的新编辑仍须由后续扫描发现。
  const sourceMtime = fs.statSync(absPath).mtimeMs;
  const sourceMd5 = await calculateFileMd5(absPath);
  const states = getAllSyncStates(ctx.root.rootId);
  const matchesCopy = (candidate: string, original: string, tag: string): boolean => {
    const source = path.posix.parse(original);
    const copy = path.posix.parse(candidate);
    return copy.dir === source.dir && copy.ext === source.ext
      && copy.name.startsWith(source.name + ' (' + tag + '-') && isConflictCopyName(copy.base);
  };
  // 1) 云端版下载为本地 "xxx (冲突-ts).ext"，并立即登记 sync_state，
  //    防止监听器把它当“本地新建”回流上传（旧实现死循环根因之一）。
  let conflictLocal = uniqueConflictName(absPath, '冲突', (p) => fs.existsSync(p));
  let cloudCopyOk = false;
  // 重放时只复用已登记且实际字节仍匹配的副本；用户修改的副本不能被覆盖或误判成功。
  for (const state of states) {
    if (state.status !== 'conflict' || state.nodeId !== item.nodeId || !item.md5
        || state.md5 !== item.md5 || !matchesCopy(state.localPath, relPath, '冲突')) continue;
    const candidate = ctx.absPathFor(state.localPath);
    if (candidate && fs.existsSync(candidate) && fs.statSync(candidate).isFile()
        && (await calculateFileMd5(candidate)) === item.md5) {
      conflictLocal = candidate;
      cloudCopyOk = true;
      break;
    }
  }
  try {
    if (!cloudCopyOk) {
      const cRel = conflictRelPath(relPath, conflictLocal, ctx.root.localPath);
      // 共用原子下载校验；落库即标记保留副本，崩溃重启也不会当作普通云端镜像清理。
      await downloadFile(ctx, item, conflictLocal, cRel, 'conflict');
      cloudCopyOk = true;
    }
  } catch (err) {
    console.error('[sync] conflict download failed:', err);
  }

  // 2) 本地版上传为云端 "xxx (本地-ts).ext"：临时文件放系统临时目录，
  //    不再在同步目录内创建/删除临时文件（旧实现触发 unlink -> 反向删云端 的循环）。
  const conflictCloudName = uniqueConflictName(path.basename(absPath), '本地', () => false);
  let localCopyOk = false;
  // 上传记录只有在当前云端节点仍保有同一哈希时才可复用，不能仅凭历史任务完成就跳过保全。
  for (const state of states) {
    if (state.status !== 'synced' || !state.nodeId || state.md5 !== sourceMd5
        || !matchesCopy(state.localPath, '/' + path.basename(relPath), '本地')) continue;
    const response = await apiClient.get(`/file/${state.nodeId}`);
    const node = response.data?.data;
    if (response.data?.code === 200 && node?.status === 0 && node.fileMd5 === sourceMd5) {
      localCopyOk = true;
      break;
    }
    if (![200, 2001, 2008].includes(response.data?.code)) throw new Error('冲突副本状态不可判定');
  }
  const tmpDir = localCopyOk ? null : fs.mkdtempSync(path.join(os.tmpdir(), 'st-sync-conflict-'));
  if (tmpDir) {
    try {
      const tempPath = path.join(tmpDir, conflictCloudName);
      fs.copyFileSync(absPath, tempPath);
      await ctx.uploadFile(tempPath, '/' + path.basename(tempPath));
      localCopyOk = true;
    } catch (err) {
      console.error('[sync] conflict local copy upload failed:', err);
    } finally {
      // 失败任务仍引用临时源文件；仅成功后清理，保留失败副本供任务重试。
      if (localCopyOk) {
        try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ignore */ }
      } else {
        syncLog('error', '冲突上传未完成，副本保留于: ' + tmpDir);
      }
    }
  }

  // 3) 两份副本都成功后才能更新原文件基线；失败时保留原节点和 MD5，让同页重放重新处理冲突。
  if (cloudCopyOk && localCopyOk) {
    upsertSyncState({ rootId: ctx.root.rootId, localPath: relPath, nodeId: item.nodeId,
      md5: item.md5 ?? undefined, localMtime: sourceMtime, status: 'conflict' });
  }
  insertSyncHistory({ rootId: ctx.root.rootId, action: 'conflict', fileName: path.basename(relPath), relPath, status: cloudCopyOk && localCopyOk ? 'success' : 'error', detail: strategy + (cloudCopyOk ? '' : '(云端副本下载失败)') + (localCopyOk ? '' : '(本地副本上传失败)') });
  emitSyncEvent('conflict', { relPath, cloudCopy: path.basename(conflictLocal) });
  if (!cloudCopyOk || !localCopyOk) throw new Error('冲突副本未完整保存，保留本页游标待重试');
}
