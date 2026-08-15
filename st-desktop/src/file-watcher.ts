import chokidar, { type FSWatcher } from 'chokidar';
import path from 'path';
import { isIgnoredLocalPath } from './sync-utils';

export interface FileChangeEvent {
  relativePath: string;
  type: 'add' | 'change' | 'unlink' | 'addDir' | 'unlinkDir';
}

/**
 * 监听本地同步目录变更（基于 chokidar）
 * 使用 awaitWriteFinish 防抖，避免大文件写入中途反复触发
 */
export class FileWatcher {
  private watcher: FSWatcher | null = null;
  private rootPath: string;
  private pending: Map<string, FileChangeEvent> = new Map();
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private onBatch: ((events: FileChangeEvent[]) => void) | null = null;

  constructor(rootPath: string) {
    this.rootPath = rootPath;
  }

  setHandler(cb: (events: FileChangeEvent[]) => void): void {
    this.onBatch = cb;
  }

  async start(): Promise<void> {
    this.watcher = chokidar.watch(this.rootPath, {
      ignoreInitial: false,
      // 忽略本地临时/系统文件（~$ Office 锁文件、.DS_Store、*.tmp 等），防止同步到云端
      ignored: (fp) => isIgnoredLocalPath(path.relative(this.rootPath, fp).split(path.sep).join('/')),
      persistent: true,
      awaitWriteFinish: { stabilityThreshold: 300, pollInterval: 100 },
      ignorePermissionErrors: true,
    });

    this.watcher.on('add', (fp) => this.enqueue(fp, 'add'));
    this.watcher.on('change', (fp) => this.enqueue(fp, 'change'));
    this.watcher.on('unlink', (fp) => this.enqueue(fp, 'unlink'));
    this.watcher.on('addDir', (fp) => this.enqueue(fp, 'addDir'));
    this.watcher.on('unlinkDir', (fp) => this.enqueue(fp, 'unlinkDir'));

    await new Promise<void>((resolve) => {
      this.watcher!.on('ready', () => resolve());
    });
  }

  private enqueue(filePath: string, type: FileChangeEvent['type']): void {
    const rel = path.relative(this.rootPath, filePath).split(path.sep).join('/');
    if (!rel) return;
    // 取最新事件类型（add 优先于 change）
    const existing = this.pending.get(rel);
    if (existing && existing.type === 'add' && type === 'change') return;
    this.pending.set(rel, { relativePath: rel, type });

    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => {
      const events = [...this.pending.values()];
      this.pending.clear();
      this.debounceTimer = null;
      if (this.onBatch && events.length > 0) {
        this.onBatch(events);
      }
    }, 500);
  }

  async stop(): Promise<void> {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    if (this.watcher) {
      await this.watcher.close();
      this.watcher = null;
    }
    this.pending.clear();
  }
}
