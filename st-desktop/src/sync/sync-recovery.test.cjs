const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, 'sync-recovery.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
}).outputText;

function fixture(t, options = {}) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-recovery-test-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true, force: true });
  });
  const desktop = path.resolve(__dirname, '../..');
  const rootParent = options.workspaceRoot
    ? fs.mkdtempSync(path.join(desktop, '.recovery-volume-test-')) : temporary;
  if (options.workspaceRoot) t.after(() => {
    assert.equal(path.dirname(rootParent), desktop);
    fs.rmSync(rootParent, { recursive: true, force: true });
  });
  const root = path.join(rootParent, 'root');
  const userData = path.join(temporary, 'userData');
  fs.mkdirSync(root); fs.mkdirSync(userData);
  const source = path.join(root, 'note.txt');
  fs.writeFileSync(source, 'v1');
  const exports = {};
  const mocks = { fs: { ...fs, ...options.fs }, path,
    electron: { app: { getPath: () => userData } },
    '../database': { getAllSyncConfigs: () => options.roots || [] } };
  vm.runInNewContext(code, { exports, require: name => {
    if (!(name in mocks)) throw Error('Unexpected dependency ' + name);
    return mocks[name];
  } });
  const run = (mark = () => {}, from = source, relative = '/note.txt') =>
    exports.preserveAndRemove('R', 'OP', root, from, relative, mark);
  return { root, source, userData, run, mocks };
}

test('TC04-30: 最后时刻编辑随原件移动', t => {
  const f = fixture(t);
  const output = f.run(() => fs.writeFileSync(f.source, 'v2'));
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v2');
  assert.equal(fs.existsSync(f.source), false);
});

test('已打开的句柄写入仍保存在恢复原件中', t => {
  const f = fixture(t);
  const fd = fs.openSync(f.source, 'r+');
  try {
    let output;
    try { output = f.run(); } catch (error) {
      // Windows 文件共享模式可能拒绝移动；此时源必须仍在，不能走删除回退。
      if (!['EPERM', 'EACCES', 'EBUSY'].includes(error.code)) throw error;
      fs.writeSync(fd, 'v2', 0, 'utf8');
      assert.equal(fs.readFileSync(f.source, 'utf8'), 'v2');
      return;
    }
    fs.writeSync(fd, 'v2', 0, 'utf8');
    assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v2');
  } finally { fs.closeSync(fd); }
});

test('目录遍历后新增文件被整体保全并记录', t => {
  const f = fixture(t);
  const folder = path.join(f.root, 'folder');
  fs.mkdirSync(folder); fs.writeFileSync(path.join(folder, 'a'), 'a');
  const output = f.run(() => fs.writeFileSync(path.join(folder, 'b'), 'new'), folder, '/folder');
  assert.equal(fs.readFileSync(path.join(output, 'files/folder/b'), 'utf8'), 'new');
  assert.ok(JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'))).entries.includes('folder/b'));
  assert.equal(fs.existsSync(folder), false);
});

test('原子移动失败保留源且同事件可以重试', t => {
  const f = fixture(t);
  f.mocks.fs.renameSync = (from, to) => {
    if (from === f.source) throw Object.assign(Error('busy'), { code: 'EBUSY' });
    return fs.renameSync(from, to);
  };
  assert.throws(() => f.run(), /busy/);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'v1');
  f.mocks.fs.renameSync = fs.renameSync;
  const output = f.run();
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
});

test('跨设备选用同步根旁目录（设备号注入）', t => {
  const f = fixture(t);
  f.mocks.fs.statSync = location => {
    const stat = fs.statSync(location);
    return location === f.userData ? { ...stat, dev: Number(stat.dev) + 1 } : stat;
  };
  const output = f.run();
  assert.ok(output.startsWith(path.join(path.dirname(f.root), '.st-cloud-sync-recovery')));
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
});

test('真实跨卷：应用目录与工作区不在同盘时，原件保存在同步根旁', t => {
  const f = fixture(t, { workspaceRoot: true });
  if (fs.statSync(f.root).dev === fs.statSync(f.userData).dev) {
    t.skip('当前环境临时目录与工作区同设备，跨设备分支由注入测试覆盖');
    return;
  }
  const output = f.run(() => fs.writeFileSync(f.source, 'cross-volume-v2'));
  assert.ok(output.startsWith(path.join(path.dirname(f.root), '.st-cloud-sync-recovery')));
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'cross-volume-v2');
  assert.equal(fs.existsSync(f.source), false);
});

test('实际 EXDEV 失败不回退为复制删除', t => {
  const f = fixture(t);
  f.mocks.fs.renameSync = (from, to) => {
    if (from === f.source) throw Object.assign(Error('EXDEV'), { code: 'EXDEV' });
    return fs.renameSync(from, to);
  };
  assert.throws(() => f.run(), /EXDEV/);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'v1');
});

test('旧 pending 副本和新源同时存在时双方均不覆盖', t => {
  const f = fixture(t);
  const output = f.run();
  const manifestPath = path.join(output, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath));
  fs.writeFileSync(manifestPath, JSON.stringify({ ...manifest, status: 'pending' }));
  fs.writeFileSync(f.source, 'new');
  assert.throws(() => f.run(), /恢复目标已存在/);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'new');
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
});

test('已完成事件重放不删除新出现的同名文件', t => {
  const f = fixture(t);
  const output = f.run();
  fs.writeFileSync(f.source, 'new');
  assert.equal(f.run(), output);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'new');
});

test('恢复位置属于其他同步根时拒绝', t => {
  const options = { roots: [] };
  const f = fixture(t, options);
  options.roots.push({ localPath: f.userData });
  assert.throws(() => f.run(), /恢复目录与同步根重叠/);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'v1');
});

test('移动后清单提交失败仍保有完整原件', t => {
  const f = fixture(t);
  let moved = false;
  f.mocks.fs.renameSync = (from, to) => {
    if (moved && from.endsWith('manifest.json.tmp')) throw Error('manifest commit failed');
    fs.renameSync(from, to);
    if (from === f.source) moved = true;
  };
  assert.throws(() => f.run(), /manifest commit failed/);
  const output = path.join(f.userData, 'sync-recovery/R/OP');
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
  assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'))).status, 'pending');
  f.mocks.fs.renameSync = fs.renameSync;
  assert.equal(f.run(), output);
  assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'))).status, 'complete');
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
});

test('TC04-29: 初始 manifest 写入失败保留源且同事件可重试', t => {
  const f = fixture(t);
  f.mocks.fs.writeFileSync = (target, ...args) => {
    if (String(target).endsWith('manifest.json.tmp')) throw Error('manifest write failed');
    return fs.writeFileSync(target, ...args);
  };
  assert.throws(() => f.run(), /manifest write failed/);
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'v1');
  f.mocks.fs.writeFileSync = fs.writeFileSync;
  const output = f.run();
  assert.equal(fs.readFileSync(path.join(output, 'files/note.txt'), 'utf8'), 'v1');
  assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'))).status, 'complete');
});

test('TC04-29: 目录移动后清单提交失败，重试补记移动期间新增文件', t => {
  const f = fixture(t);
  const folder = path.join(f.root, 'folder');
  fs.mkdirSync(folder);
  fs.writeFileSync(path.join(folder, 'a.txt'), 'a-original');
  let moved = false;
  f.mocks.fs.renameSync = (from, to) => {
    if (moved && from.endsWith('manifest.json.tmp')) throw Error('manifest commit failed');
    if (from === folder) fs.writeFileSync(path.join(folder, 'b.txt'), 'b-late');
    fs.renameSync(from, to);
    if (from === folder) moved = true;
  };
  assert.throws(() => f.run(() => {}, folder, '/folder'), /manifest commit failed/);
  f.mocks.fs.renameSync = fs.renameSync;
  const output = f.run(() => {}, folder, '/folder');
  assert.equal(fs.readFileSync(path.join(output, 'files/folder/a.txt'), 'utf8'), 'a-original');
  assert.equal(fs.readFileSync(path.join(output, 'files/folder/b.txt'), 'utf8'), 'b-late');
  const manifest = JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'), 'utf8'));
  assert.equal(manifest.status, 'complete');
  assert.deepEqual(manifest.entries.sort(), ['folder/a.txt', 'folder/b.txt']);
});

test('TC04-10: 恢复目录联接指向外部时不写入外部清单或移动源', t => {
  const f = fixture(t);
  const outside = path.join(path.dirname(f.userData), 'outside');
  fs.mkdirSync(path.join(outside, 'R/OP'), { recursive: true });
  const sentinel = path.join(outside, 'R/OP/manifest.json.tmp');
  fs.writeFileSync(sentinel, 'outside-sentinel');
  fs.symlinkSync(outside, path.join(f.userData, 'sync-recovery'), process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => f.run(), /符号链接|恢复目录/);
  assert.equal(fs.readFileSync(sentinel, 'utf8'), 'outside-sentinel');
  assert.equal(fs.readFileSync(f.source, 'utf8'), 'v1');
});
