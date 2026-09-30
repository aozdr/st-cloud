import fs from 'fs';
import path from 'path';
import { apiClient } from '../api-client';
import { getSyncState, getAllSyncStates, deleteSyncState, upsertSyncState } from '../database';
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
    const seen = new Map<string, string>();
    const downloaded = await reconcileFolder(ctx, ctx.root.cloudFolderNodeId, '', seen);
    const rootResponse = await withRetry(() => apiClient.get(`/file/${ctx.root.cloudFolderNodeId}`), '读取同步根');
    if (rootResponse.data?.code !== 200 || typeof rootResponse.data?.data?.path !== 'string') {
      throw new Error('同步根详情不可用');
    }
    const rootPath: string = rootResponse.data.data.path;
    const handled: string[] = [];
    for (const state of oldStates.sort((a, b) => a.localPath.length - b.localPath.length)) {
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
      const preserve = fs.existsSync(oldAbs) ? preserveAndRemove : resumeRecoveryIfPresent;
      const recovery = preserve(ctx.root.rootId, 'legacy-' + state.nodeId,
        ctx.root.localPath, oldAbs, state.localPath, rel => ctx.markEngineWritten(rel));
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
    // 分页必须是服务端明确给出的整数；null/空串转成 0 会把损坏响应误判为空目录。
    if (!payload || !Array.isArray(payload.records) || !Number.isInteger(payload.pages)
        || payload.pages < 0 || (payload.pages === 0 && payload.records.length > 0)
        || (payload.pages > 0 && payload.pages < page)) throw new Error('云端目录响应不完整');
    const records: Array<{
      id: string; parentId: string; nodeType: number; name: string;
      path: string; fileSize: string | number | null; fileMd5: string | null; updatedAt: string;
    }> = payload?.records ?? [];
    const totalPages: number = payload.pages;

    for (const node of records) {
      const fileSize = parseFileSize(node.fileSize);
      const relPath = relPrefix + '/' + node.name;
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
