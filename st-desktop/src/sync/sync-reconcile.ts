import fs from 'fs';
import path from 'path';
import { apiClient } from '../api-client';
import { getSyncState, upsertSyncState } from '../database';
import { calculateFileMd5 } from '../utils/md5';
import { withRetry, syncLog, type SyncEngineCtx, type DeltaItem } from './sync-shared';

/**
 * 同步引擎全量对账模块：递归列举云端目录，下载本地缺失文件，内容不一致走冲突流程。
 * 作为增量 delta 的安全网，捕获 sync_change_log 缺失的历史文件。
 * 拆分自 sync-engine.ts，方法体逻辑保持不变（this -> ctx）。
 */

export async function fullReconcile(ctx: SyncEngineCtx): Promise<boolean> {
  syncLog('info', '开始全量对账...');
  try {
    const downloaded = await reconcileFolder(ctx, ctx.root.cloudFolderNodeId, '');
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
async function reconcileFolder(ctx: SyncEngineCtx, folderId: string, relPrefix: string): Promise<number> {
  let downloaded = 0;
  let page = 1;
  let hasMore = true;
  while (hasMore) {
    const res = await withRetry(
      () => apiClient.get('/file/list', { params: { parentId: folderId, page, size: 100 } }),
      '全量对账列举文件',
    );
    const payload = res.data?.data ?? res.data;
    const records: Array<{
      id: string; parentId: string; nodeType: number; name: string;
      path: string; fileSize: number; fileMd5: string | null; updatedAt: string;
    }> = payload?.records ?? [];
    const totalPages: number = payload?.pages ?? 1;

    for (const node of records) {
      const relPath = relPrefix + '/' + node.name;
      if (ctx.isExcluded(relPath)) continue;
      const absPath = ctx.absPathFor(relPath);
      if (!absPath) continue;

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
        downloaded += await reconcileFolder(ctx, node.id, relPath);
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
            size: node.fileSize ?? null,
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
            if (stat.size === (node.fileSize ?? -1)) {
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
                size: node.fileSize ?? null,
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
              size: state.size ?? node.fileSize ?? undefined,
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
