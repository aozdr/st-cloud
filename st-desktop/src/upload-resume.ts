import type { TransferTask } from './types';

export const RELAY_RESTART_REQUIRED = '应用重启后限速中转会话不可安全恢复，请重新开始上传';

export type UploadResumePlan = 'direct' | 'relay' | 'restart-required';

/** 恢复策略纯函数：relay 永远不能降级为 chunk-url/PUT/merge。 */
export function resolveUploadResumePlan(task: Pick<TransferTask, 'transferMode' | 'error'>): UploadResumePlan {
  if (task.transferMode !== 'relay') return 'direct';
  if (task.error === RELAY_RESTART_REQUIRED) return 'restart-required';
  return 'relay';
}
