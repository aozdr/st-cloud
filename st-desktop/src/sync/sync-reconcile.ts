import fs from 'fs';
import path from 'path';
import { createHash } from 'crypto';
import { apiClient } from '../api-client';
import { getSyncState, getAllSyncStates, getSyncConfig, deleteSyncState, upsertSyncState } from '../database';
import { calculateFileMd5 } from '../utils/md5';
import { withRetry, syncLog, parseFileSize, type SyncEngineCtx, type DeltaItem } from './sync-shared';
import { preserveAndRemove, resumeRecoveryIfPresent } from './sync-recovery';

/**
 * 同步引擎全量对账模块：递归列举云端目录，下载本地缺失文件，内容不一致走冲突流程。
 * 作为增量 delta 的安全网，捕获 sync_change_log 缺失的历史文件。
 * 拆分自 sync-engine.ts，方法体逻辑保持不变（this -> ctx）。
 */

export async function fullReconcile(ctx: SyncEngineCtx): Promise<boolean> {
  syncLog('info', '开始全量对账...');
  try {
    const oldStates = getAllSyncStates(ctx.root.rootId);
    const baselineCursor = getSyncConfig(ctx.root.rootId)?.cursor ?? '0';
    const seen = new Map<string, string>();
    const downloaded = await reconcileFolder(ctx, ctx.root.cloudFolderNodeId, '', seen);
    const rootResponse = await withRetry(() => apiClient.get(`/file/${ctx.root.cloudFolderNodeId}`), '读取同步根');
    if (rootResponse.data?.code !== 200 || typeof rootResponse.data?.data?.path !== 'string') {
      throw new Error('同步根详情不可用');
    }
    const rootPath: string = rootResponse.data.data.path;
    // 同步根自身的 CREATE 可以登记根映射；它属于扫描结果，不能当作历史残留搬走。
    seen.set(ctx.root.cloudFolderNodeId, '/');
    const handled: string[] = [];
    for (const state of oldStates.sort((a, b) => a.localPath.length - b.localPath.length)) {
      const canonicalPath = state.localPath.replace(/\/+/g, '/');
      if (canonicalPath !== state.localPath && state.nodeId && seen.get(state.nodeId) === canonicalPath) {
        const canonicalState = getSyncState(ctx.root.rootId, canonicalPath);
        const oldAbs = ctx.absPathFor(state.localPath);
        const canonicalAbs = ctx.absPathFor(canonicalPath);
        // 只清除同节点、同实际文件的重复斜杠映射，不移动字节，也不覆盖其他节点的状态。
        if (oldAbs && canonicalAbs && oldAbs === canonicalAbs
            && (canonicalState?.nodeId === state.nodeId
              || (canonicalPath === '/' && state.nodeId === ctx.root.cloudFolderNodeId))) {
          deleteSyncState(ctx.root.rootId, state.localPath);
          continue;
        }
      }
      // 冲突副本是本地保留内容，并非云端路径镜像；相同云端节点可对应多个副本，不能当历史残留搬走。
      if (state.status === 'conflict') continue;
      if (!state.nodeId || seen.get(state.nodeId) === state.localPath || ctx.isExcluded(state.localPath)
          || handled.some(parent => state.localPath.startsWith(parent + '/'))) continue;
      // 云端新节点已占用旧路径时，只清理旧节点的映射，不能移走新节点的内容与状态。
      const occupant = getSyncState(ctx.root.rootId, state.localPath);
      if (occupant?.nodeId && occupant.nodeId !== state.nodeId) continue;
      if (!seen.has(state.nodeId)) {
        const detail = await withRetry(() => apiClient.get(`/file/${state.nodeId}`), '核对历史节点');
        const body = detail.data;
        if (body?.code !== 200 && body?.code !== 2001 && body?.code !== 2008) {
          throw new Error('历史节点状态不可判定');
        }
        const pathNow: string | null = body?.code === 200 ? body.data?.path : null;
        if (pathNow && (pathNow === rootPath || pathNow.startsWith(rootPath + '/'))) {
          // 云端扫描期间发生移动，留给下一轮增量处理，不能误删。
          throw new Error('历史节点仍在同步根中，等待增量对账');
        }
      }
      const oldAbs = ctx.absPathFor(state.localPath);
      if (!oldAbs) throw new Error('历史状态路径越界');
      // 原件可能在上次对账中已移入恢复区，清理旧映射前先确认该恢复操作完成。
      // 同节点后续移动/修改必须独立保全；同一基线的失败重试则使用稳定 ID，保留所有旧副本。
      const generation = createHash('sha256').update(JSON.stringify([state.nodeId, state.localPath,
        state.md5, state.size, state.localMtime, state.cloudMtime, baselineCursor])).digest('hex');
      const operationId = 'legacy-' + state.nodeId + '-' + generation;
      const args = [ctx.root.localPath, oldAbs, state.localPath,
        (rel: string) => ctx.markEngineWritten(rel)] as const;
      const recovery = fs.existsSync(oldAbs)
        ? preserveAndRemove(ctx.root.rootId, operationId, ...args)
        : resumeRecoveryIfPresent(ctx.root.rootId, operationId, ...args)
          // 兼容升级前已原子移动但未提交清单的操作；其他路径的旧清单不能占用当前保全轮次。
          ?? resumeRecoveryIfPresent(ctx.root.rootId, 'legacy-' + state.nodeId, ...args,
            { ignoreUnrelatedPath: true });
      if (recovery) syncLog('conflict', '历史残留已保存到: ' + recovery);
      for (const row of getAllSyncStates(ctx.root.rootId)) {
        if (row.localPath === state.localPath || row.localPath.startsWith(state.localPath + '/')) {
          deleteSyncState(ctx.root.rootId, row.localPath);
        }
      }
      handled.push(state.localPath);
    }
    if (downloaded > 0) {
      syncLog('info', `全量对账完成，下载了 ${downloaded} 个缺失文件`);
    } else {
      syncLog('info', '全量对账完成，无缺失文件');
    }
    return true;
  } catch (err) {
    syncLog('error', '全量对账失败: ' + String(err));
    return false;
  }
}

/**
 * 递归对账文件夹：列举云端子节点，下载本地缺失的文件，对子文件夹递归。
 * @param folderId 云端文件夹节点 ID
 * @param relPrefix 相对路径前缀（根为 "" ，子文件夹为 "/subfolder" ）
 * @returns 本次下载的文件数
 */
export async function reconcileFolder(ctx: SyncEngineCtx, folderId: string, relPrefix: string,
                                      seen?: Map<string, string>): Promise<number> {
  let downloaded = 0;
  let page = 1;
  let hasMore = true;
  while (hasMore) {
    const res = await withRetry(
      () => apiClient.get('/file/list', { params: { parentId: folderId, page, size: 100 } }),
      '全量对账列举文件',
    );
    if (res.data?.code != null && res.data.code !== 200) {
      throw new Error('云端目录列举失败: ' + String(res.data.message || res.data.code));
    }
    const payload = res.data?.data ?? res.data;
    // 后端 Long 全局序列化为十进制字符串；仅接受安全非负整数，不能把 null/空串误判为空目录。
    const rawPages: unknown = payload?.pages;
    const totalPages = typeof rawPages === 'number' ? rawPages
      : typeof rawPages === 'string' && /^(0|[1-9][0-9]*)$/.test(rawPages) ? Number(rawPages) : NaN;
    if (!payload || !Array.isArray(payload.records) || !Number.isSafeInteger(totalPages)
        || totalPages < 0 || (totalPages === 0 && payload.records.length > 0)
        || (totalPages > 0 && totalPages < page)) throw new Error('云端目录响应不完整');
    const records: Array<{
      id: string; parentId: string; nodeType: number; name: string;
      path: string; fileSize: string | number | null; fileMd5: string | null; updatedAt: string;
    }> = payload?.records ?? [];

    for (const node of records) {
      const fileSize = parseFileSize(node.fileSize);
      // 根事件的 '/' 前缀与全量扫描的空前缀使用同一规范路径，避免生成 '//文件' 状态。
      const relPath = relPrefix.replace(/\/+/g, '/').replace(/\/$/, '') + '/' + node.name;
      if (ctx.isExcluded(relPath)) continue;
      if (seen) seen.set(node.id, relPath);
      const absPath = ctx.absPathFor(relPath);
      if (!absPath) throw new Error('云端目录路径越界');

      if (node.nodeType === 0) {
        // 文件夹：确保本地存在，递归对账
        if (!fs.existsSync(absPath)) {
          fs.mkdirSync(absPath, { recursive: true });
          syncLog('create', '创建文件夹(对账): ' + node.name);
        }
        const state = getSyncState(ctx.root.rootId, relPath);
        if (!state) {
          upsertSyncState({ rootId: ctx.root.rootId, localPath: relPath, nodeId: node.id, status: 'synced', cloudMtime: node.updatedAt });
        } else if (state.nodeId !== node.id) {
          // 目录状态存在但 node_id 与云端不一致（历史精度污染/漂移）：以云端为准刷新
          upsertSyncState({ rootId: ctx.root.rootId, localPath: relPath, nodeId: node.id, status: 'synced', cloudMtime: node.updatedAt });
        }
        downloaded += await reconcileFolder(ctx, node.id, relPath, seen);
      } else {
        // 文件：本地不存在则下载；本地已存在则按内容比对决定“登记 / 冲突保留 / 刷新 node_id”
        const localExists = fs.existsSync(absPath);
        const state = getSyncState(ctx.root.rootId, relPath);
        if (!localExists) {
          const item: DeltaItem = {
            logId: 'reconcile',
            nodeId: node.id,
            parentId: node.parentId,
            changeType: 'CREATE',
            path: relPath,
            oldPath: null,
            name: node.name,
            nodeType: node.nodeType,
            size: fileSize,
            md5: node.fileMd5 ?? null,
            suffix: null,
            status: 0,
            updatedAt: node.updatedAt,
          };
          await ctx.downloadFile(item, absPath, relPath);
          downloaded++;
        } else {
          // 本地存在：云端 md5 与已登记 md5 不一致时才需要进一步处理
          const stat = fs.statSync(absPath);
          const cloudMd5 = node.fileMd5 ?? null;
          const stateMd5 = state?.md5 ?? null;
          if (cloudMd5 && stateMd5 !== cloudMd5) {
            // 同名且内容不一致：计算本地 md5（大小不同则直接判定不同，省一次全量哈希）
            let localMd5: string | null = null;
            if (stat.size === (fileSize ?? -1)) {
              localMd5 = await calculateFileMd5(absPath).catch(() => null);
            }
            if (localMd5 === cloudMd5) {
              // 内容实际一致：只登记状态（避免重复下载覆盖本地）
              upsertSyncState({ rootId: ctx.root.rootId,
                localPath: relPath,
                nodeId: node.id,
                md5: cloudMd5,
                size: stat.size,
                localMtime: stat.mtimeMs,
                cloudMtime: node.updatedAt,
                status: 'synced',
              });
            } else {
              if (state?.status === 'needs_review') {
                throw new Error('本地文件缺少可信同步基线，需人工核对: ' + relPath);
              }
              // 同名且内容不一致：按冲突流程保留两份，绝不静默覆盖本地修改
              const item: DeltaItem = {
                logId: 'reconcile',
                nodeId: node.id,
                parentId: node.parentId,
                changeType: 'UPDATE',
                path: relPath,
                oldPath: null,
                name: node.name,
                nodeType: node.nodeType,
                size: fileSize,
                md5: cloudMd5,
                suffix: null,
                status: 0,
                updatedAt: node.updatedAt,
              };
              await ctx.handleConflict(absPath, relPath, item);
            }
          } else if (state && state.nodeId !== node.id) {
            // 本地与云端内容一致（md5 相同），但记录的 node_id 与云端不一致：
            // 以云端为准刷新 node_id/cloud_mtime 并清除失败退避，修复后立即恢复同步
            upsertSyncState({ rootId: ctx.root.rootId,
              localPath: relPath,
              nodeId: node.id,
              md5: state.md5 ?? node.fileMd5 ?? undefined,
              size: state.size ?? fileSize ?? undefined,
              localMtime: state.localMtime,
              cloudMtime: node.updatedAt,
              status: 'synced',
              failCount: 0,
              failMtime: undefined,
              nextRetryAt: undefined,
            });
          }
        }
      }
    }
    hasMore = page < totalPages;
    page++;
  }
  return downloaded;
}
