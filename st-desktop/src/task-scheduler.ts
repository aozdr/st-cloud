/**
 * 传输任务调度器 —— 控制最大并行任务数
 * upload-manager 和 download-manager 共享使用
 */

let maxParallelTasks = 3;

/** 正在执行的任务 ID 集合 */
const activeTasks = new Set<string>();

/** 等待执行的任务队列 */
const pendingQueue: Array<{ taskId: string; run: () => Promise<void> }> = [];

export function setMaxParallelTasks(max: number): void {
  maxParallelTasks = max;
  drainQueue();
}

export function getMaxParallelTasks(): number {
  return maxParallelTasks;
}

/**
 * 调度一个任务。有空闲槽位则立即执行，否则入队等待。
 * run() 完成后（无论成功/失败/暂停）自动释放槽位并触发队列消费。
 */
export function scheduleTask(taskId: string, run: () => Promise<void>): void {
  if (activeTasks.size < maxParallelTasks) {
    activeTasks.add(taskId);
    run().finally(() => {
      activeTasks.delete(taskId);
      drainQueue();
    });
  } else {
    pendingQueue.push({ taskId, run });
  }
}

/**
 * 手动释放任务槽位（如用户暂停任务时）。
 * 会触发队列消费，让等待中的任务开始执行。
 */
export function releaseTask(taskId: string): void {
  if (activeTasks.has(taskId)) {
    activeTasks.delete(taskId);
    drainQueue();
  }
}

/**
 * 从等待队列中移除任务（如用户取消任务时）。
 */
export function cancelPendingTask(taskId: string): void {
  const idx = pendingQueue.findIndex((t) => t.taskId === taskId);
  if (idx >= 0) {
    pendingQueue.splice(idx, 1);
  }
}

function drainQueue(): void {
  while (activeTasks.size < maxParallelTasks && pendingQueue.length > 0) {
    const { taskId, run } = pendingQueue.shift()!;
    activeTasks.add(taskId);
    run().finally(() => {
      activeTasks.delete(taskId);
      drainQueue();
    });
  }
}
