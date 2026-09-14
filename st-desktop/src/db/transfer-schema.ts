import type { Database } from 'sql.js';

/** transfer_tasks 的增量迁移集中在这里，测试可直接使用旧表验证升级。 */
export function ensureTransferTaskColumns(db: Database): void {
  try { db.run('ALTER TABLE transfer_tasks ADD COLUMN transfer_mode TEXT'); } catch { /* 列已存在 */ }
  try { db.run('ALTER TABLE transfer_tasks ADD COLUMN relay_chunk_size INTEGER'); } catch { /* 列已存在 */ }
  try { db.run('ALTER TABLE transfer_tasks ADD COLUMN relay_limit_kb INTEGER'); } catch { /* 列已存在 */ }
}
