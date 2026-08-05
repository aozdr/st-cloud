import { apiClient, getUserId } from './api-client';
import { SyncEngine, type SyncRootInfo } from './sync-engine';
import { getAllSyncConfigs, deleteSyncConfig, upsertSyncConfig, claimLegacySyncConfigs } from './database';

interface CloudSyncRootVO {
  id: string;
  cloudFolderNodeId: string;
  cloudFolderName: string;
  localPathHint: string | null;
  status: number;
  cursor: number;
}

const engines = new Map<string, SyncEngine>();

/**
 * 启动一个同步根的同步引擎
 */
export async function startSync(rootId: string, cloudFolderNodeId: string, localPath: string): Promise<void> {
  if (engines.has(rootId)) return;

  const info: SyncRootInfo = {
    rootId,
    cloudFolderNodeId,
    localPath,
  };

  const engine = new SyncEngine(info);
  engines.set(rootId, engine);
  try {
    await engine.start();
  } catch (err) {
    engines.delete(rootId);
    throw err;
  }
}

export async function stopSync(rootId: string): Promise<void> {
  const engine = engines.get(rootId);
  if (engine) {
    await engine.stop();
    engines.delete(rootId);
  }
}

export async function stopAllSync(): Promise<void> {
  for (const [id, engine] of engines) {
    await engine.stop();
    engines.delete(id);
  }
}

export function isSyncing(rootId: string): boolean {
  const engine = engines.get(rootId);
  return engine?.isRunning() ?? false;
}

export function getSyncStatus(): Record<string, boolean> {
  const result: Record<string, boolean> = {};
  for (const [id, engine] of engines) {
    result[id] = engine.isRunning();
  }
  return result;
}

/**
 * 注册同步根：云端注册 + 本地配置落库 + 自动启动引擎
 */
export async function registerSyncRoot(cloudFolderNodeId: string, localPath: string): Promise<CloudSyncRootVO> {
  const res = await apiClient.post('/sync/roots', { cloudFolderNodeId, localPathHint: localPath });
  const body = res.data;
  const root: CloudSyncRootVO = body?.data ?? body;
  if (!root) throw new Error('注册同步根失败');

  const rootId = root.id;
  upsertSyncConfig({ rootId, localPath, cursor: 0, status: 'active', userId: getUserId() ?? undefined });
  await startSync(rootId, root.cloudFolderNodeId, localPath);

  return root;
}

export async function listSyncRoots(): Promise<CloudSyncRootVO[]> {
  const res = await apiClient.get('/sync/roots');
  const body = res.data;
  return body?.data ?? body ?? [];
}

export async function deleteSyncRoot(rootId: string): Promise<void> {
  await stopSync(rootId);
  await apiClient.delete(`/sync/roots/${rootId}`);
  deleteSyncConfig(rootId);
}

/**
 * 应用启动时恢复所有 active 同步根
 */
export async function resumeSyncEngines(): Promise<void> {
  const userId = getUserId();
  if (!userId) {
    console.log('[sync] resume skipped: no user identified');
    return;
  }
  claimLegacySyncConfigs(userId);
  const allConfigs = getAllSyncConfigs(userId);
  const configs = allConfigs.filter((c) => c.status === 'active');
  if (configs.length === 0) return;

  console.log('[sync] resume: found', configs.length, 'active config(s)', configs.map(c => c.rootId));

  let roots: CloudSyncRootVO[] = [];
  try {
    const res = await apiClient.get('/sync/roots');
    const body = res.data;
    roots = body?.data ?? body ?? [];
    console.log('[sync] cloud returned', roots.length, 'root(s)', roots.map(r => r.id));

    for (const cfg of configs) {
      const cloudRoot = roots.find((r) => String(r.id) === String(cfg.rootId));
      if (cloudRoot) {
        try {
          await startSync(cfg.rootId, cloudRoot.cloudFolderNodeId, cfg.localPath);
          console.log('[sync] resumed root', cfg.rootId, '->', cloudRoot.cloudFolderName || cloudRoot.cloudFolderNodeId);
        } catch (err) {
          console.error('[sync] resume failed for root', cfg.rootId, err);
        }
      } else {
        console.warn('[sync] root not found on cloud, removing local config:', cfg.rootId);
        deleteSyncConfig(cfg.rootId);
      }
    }
  } catch (err) {
    console.warn('[sync] resume skipped (auth not ready or network error):', String(err).substring(0, 100));
  }

  // Auto-relink: if cloud has roots without local config, try to resume them
  // (covers the case where local config was deleted due to stale/precision-lost ID)
  try {
    const localConfigs = getAllSyncConfigs(userId);
    const orphaned = roots.filter(cr => !localConfigs.some(lc => String(lc.rootId) === String(cr.id)));

    for (const cloudRoot of orphaned) {
      // Only auto-relink if the cloud root has a localPathHint we can use
      const localPath = cloudRoot.localPathHint || '';
      if (!localPath) {
        console.info('[sync] cloud root', cloudRoot.id, 'has no localPathHint, skipping auto-relink');
        continue;
      }
      try {
        upsertSyncConfig({ rootId: cloudRoot.id, localPath, cursor: cloudRoot.cursor || 0, status: 'active' });
        await startSync(cloudRoot.id, cloudRoot.cloudFolderNodeId, localPath);
        console.log('[sync] auto-relinked orphan root', cloudRoot.id, '->', cloudRoot.cloudFolderName || cloudRoot.cloudFolderNodeId);
      } catch (err) {
        console.error('[sync] auto-relink failed for root', cloudRoot.id, err);
      }
    }
  } catch {
    // ignore
  }
}