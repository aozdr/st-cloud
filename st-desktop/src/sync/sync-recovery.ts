import fs from 'fs';
import path from 'path';
import { app } from 'electron';
import { getAllSyncConfigs } from '../database';

function within(parent: string, target: string): boolean {
  const relative = path.relative(path.resolve(parent), path.resolve(target));
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}

/** 恢复路径不能经目录联接/符号链接重定向；必须在创建目录或写清单之前检查。 */
function assertNoRecoveryLinks(anchor: string, target: string): void {
  const base = path.resolve(anchor);
  if (!within(base, target)) throw new Error('恢复路径越界');
  let current = base;
  for (const segment of path.relative(base, target).split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    try {
      if (fs.lstatSync(current).isSymbolicLink()) throw new Error('恢复路径含符号链接，停止处理');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') break;
      throw error;
    }
  }
}

/** 源已移动时仍须完成已有恢复清单；没有本事件清单时保持删除重放的空操作语义。 */
export function resumeRecoveryIfPresent(rootId: string, operationId: string, rootPath: string,
                                       sourcePath: string, relativePath: string,
                                       markWritten: (relative: string) => void,
                                       options?: { ignoreUnrelatedPath?: boolean }): string | null {
  if (!/^[\w-]+$/.test(rootId) || !/^[\w-]+$/.test(operationId)) throw new Error('恢复事件标识非法');
  const realRoot = fs.realpathSync(rootPath);
  const bases = [path.join(app.getPath('userData'), 'sync-recovery'),
    path.join(path.dirname(realRoot), '.st-cloud-sync-recovery')];
  const exists = bases.some(base => {
    const manifestPath = path.join(base, rootId, operationId, 'manifest.json');
    assertNoRecoveryLinks(path.dirname(base), manifestPath);
    if (!fs.existsSync(manifestPath)) return false;
    // 仅升级兼容的旧节点 ID 清单需要按原路径筛选；损坏清单与身份不符仍由正常校验拒绝。
    if (options?.ignoreUnrelatedPath) {
      const previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
      if (previous.rootId !== rootId || previous.operationId !== operationId) throw new Error('恢复清单与当前事件不一致');
      if (previous.originalPath !== relativePath) return false;
    }
    return true;
  });
  if (!exists) return null;
  return preserveAndRemove(rootId, operationId, rootPath, sourcePath, relativePath, markWritten);
}

/** 云端删除前原子保全本地原件；恢复目录必须同盘且不可与任何同步根重叠。 */
export function preserveAndRemove(rootId: string, operationId: string, rootPath: string,
                                  sourcePath: string, relativePath: string,
                                  markWritten: (relative: string) => void): string {
  const root = path.resolve(rootPath);
  const source = path.resolve(sourcePath);
  if (source === root || !within(root, source)) throw new Error('拒绝删除同步根或越界路径');
  const realRoot = fs.realpathSync(root);
  if (!/^[\w-]+$/.test(rootId) || !/^[\w-]+$/.test(operationId)) throw new Error('恢复事件标识非法');
  const relative = relativePath.replace(/^\/+/, '');
  if (path.resolve(root, relative) !== source) throw new Error('恢复相对路径与源文件不一致');
  const userData = app.getPath('userData');
  const syncRoots = [root, ...getAllSyncConfigs().map(config => config.localPath)];
  const overlaps = (location: string): boolean => syncRoots.some(syncRoot => {
    const real = fs.existsSync(syncRoot) ? fs.realpathSync(syncRoot) : path.resolve(syncRoot);
    return within(real, location) || within(location, real);
  });
  // 原件可能已移动成功，但最终 complete 清单提交失败；重试时从已保存的 pending 副本收尾。
  if (!fs.existsSync(source)) {
    const bases = [path.join(userData, 'sync-recovery'), path.join(path.dirname(realRoot), '.st-cloud-sync-recovery')];
    for (const base of bases) {
      const recovery = path.resolve(base, rootId, operationId);
      const manifestPath = path.join(recovery, 'manifest.json');
      assertNoRecoveryLinks(path.dirname(base), manifestPath);
      assertNoRecoveryLinks(path.dirname(base), manifestPath + '.tmp');
      if (!fs.existsSync(manifestPath)) continue;
      if (overlaps(recovery) || overlaps(fs.realpathSync(recovery))) throw new Error('恢复目录与同步根重叠');
      const previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as {
        status: string; rootId: string; operationId: string; originalPath: string; entries: string[];
      };
      if (previous.rootId !== rootId || previous.operationId !== operationId
          || previous.originalPath !== relativePath || !Array.isArray(previous.entries)) {
        throw new Error('恢复清单与当前事件不一致');
      }
      const filesRoot = path.join(recovery, 'files');
      const destination = path.resolve(filesRoot, relative);
      assertNoRecoveryLinks(recovery, destination);
      if (!within(filesRoot, destination) || !fs.existsSync(destination)
          || !within(fs.realpathSync(filesRoot), fs.realpathSync(destination))) {
        throw new Error('恢复副本缺失或越界，停止确认');
      }
      for (const entry of previous.entries) {
        if (typeof entry !== 'string') throw new Error('恢复清单条目非法');
        const saved = path.resolve(filesRoot, entry);
        if (!within(destination, saved) || !fs.existsSync(saved)
            || fs.lstatSync(saved).isSymbolicLink()
            || !within(fs.realpathSync(filesRoot), fs.realpathSync(saved))) {
          throw new Error('恢复清单中的旧副本缺失，停止确认');
        }
      }
      if (previous.status === 'complete') return recovery;
      if (previous.status !== 'pending') throw new Error('恢复清单状态非法');
      const entries: string[] = [];
      const visitSaved = (current: string, rel: string): void => {
        const stat = fs.lstatSync(current);
        if (stat.isSymbolicLink()) throw new Error('恢复副本含符号链接，停止确认');
        if (stat.isDirectory()) {
          for (const name of fs.readdirSync(current)) visitSaved(path.join(current, name), path.posix.join(rel, name));
        } else if (stat.isFile()) entries.push(rel);
        else throw new Error('恢复副本含特殊文件，停止确认');
      };
      visitSaved(destination, relative);
      const stagingPath = manifestPath + '.tmp';
      fs.writeFileSync(stagingPath, JSON.stringify({ ...previous, status: 'complete',
        entries: [...new Set([...previous.entries, ...entries])] }, null, 2));
      fs.renameSync(stagingPath, manifestPath);
      for (const entry of entries) markWritten('/' + entry.split(path.sep).join('/'));
      return recovery;
    }
    throw new Error('恢复源和已保存副本均不存在');
  }
  const realSource = fs.realpathSync(source);
  if (realSource === realRoot || !within(realRoot, realSource)) throw new Error('符号链接指向同步根外，停止清理');
  // 跨盘不能原子 rename，改在同步根旁保留原件；绝不回退到 copy + unlink。
  const base = fs.statSync(userData).dev === fs.statSync(source).dev
    ? path.join(userData, 'sync-recovery')
    : path.join(path.dirname(realRoot), '.st-cloud-sync-recovery');
  const recovery = path.resolve(base, rootId, operationId);
  assertNoRecoveryLinks(path.dirname(base), recovery);
  if (overlaps(recovery)) {
    throw new Error('恢复目录与同步根重叠');
  }
  const files: Array<{ source: string; relative: string }> = [];
  const visit = (current: string, rel: string): void => {
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink()) throw new Error('同步目录含符号链接，已停止自动清理');
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(current)) visit(path.join(current, name), path.posix.join(rel, name));
    } else if (stat.isFile()) {
      files.push({ source: current, relative: rel });
    } else {
      throw new Error('同步目录含特殊文件，已停止自动清理');
    }
  };
  visit(source, relative);
  fs.mkdirSync(recovery, { recursive: true });
  assertNoRecoveryLinks(path.dirname(base), recovery);
  if (overlaps(fs.realpathSync(recovery))) throw new Error('恢复目录落入同步根，停止清理');
  const manifestPath = path.join(recovery, 'manifest.json');
  assertNoRecoveryLinks(recovery, manifestPath);
  assertNoRecoveryLinks(recovery, manifestPath + '.tmp');
  let previousEntries: string[] = [];
  if (fs.existsSync(manifestPath)) {
    const previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as {
      status: string; rootId: string; operationId: string; originalPath: string; entries: string[];
    };
    if (previous.rootId !== rootId || previous.operationId !== operationId
        || previous.originalPath !== relativePath) throw new Error('恢复清单与当前事件不一致');
    if (previous.status === 'complete') return recovery;
    previousEntries = previous.entries;
  }
  const manifest = { status: 'pending', rootId, operationId, originalPath: relativePath,
    entries: [...new Set([...previousEntries, ...files.map(file => file.relative)])] };
  const stagingPath = manifestPath + '.tmp';
  fs.writeFileSync(stagingPath, JSON.stringify(manifest, null, 2));
  fs.renameSync(stagingPath, manifestPath);
  for (const entry of previousEntries) {
    const saved = path.resolve(recovery, 'files', entry);
    const remaining = path.resolve(root, entry);
    if (!within(path.join(recovery, 'files'), saved) || !within(root, remaining)
        || (!fs.existsSync(saved) && !fs.existsSync(remaining))) {
      throw new Error('恢复清单中的旧副本缺失，停止清理');
    }
  }
  const destination = path.resolve(recovery, 'files', relative);
  if (!within(path.join(recovery, 'files'), destination)) throw new Error('恢复路径越界');
  assertNoRecoveryLinks(recovery, destination);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  if (!within(fs.realpathSync(recovery), fs.realpathSync(path.dirname(destination)))) {
    throw new Error('恢复目标含越界链接');
  }
  // 旧 pending 副本不可覆盖。异常时保留两份内容，避免重试吞掉用户的新编辑。
  if (fs.existsSync(destination)) throw new Error('恢复目标已存在，保留源文件并停止清理');
  for (const file of files) markWritten('/' + file.relative.split(path.sep).join('/'));
  // 一次移动整个目录，遍历后新增文件也一起保全；打开句柄仍引用原件而非过时副本。
  fs.renameSync(source, destination);
  files.length = 0;
  visit(destination, relative);
  fs.writeFileSync(stagingPath, JSON.stringify({ ...manifest, status: 'complete',
    entries: [...new Set([...previousEntries, ...files.map(file => file.relative)])] }, null, 2));
  fs.renameSync(stagingPath, manifestPath);
  return recovery;
}
