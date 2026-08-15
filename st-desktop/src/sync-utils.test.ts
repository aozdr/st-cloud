import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  isConflictCopyName,
  uniqueConflictName,
  isLocallyChanged,
  conflictRelPath,
  isIgnoredLocalPath,
} from './sync-utils';

test('isIgnoredLocalPath 过滤 Office 锁文件与系统临时文件', () => {
  // Office/OnlyOffice 临时锁文件（~$ 开头）必须忽略
  assert.equal(isIgnoredLocalPath('/docs/~$客户对象导出结果.xlsx'), true);
  assert.equal(isIgnoredLocalPath('~$报告.docx'), true);
  // 系统元数据
  assert.equal(isIgnoredLocalPath('/.DS_Store'), true);
  assert.equal(isIgnoredLocalPath('/Thumbs.db'), true);
  assert.equal(isIgnoredLocalPath('/docs/desktop.ini'), true);
  // 编辑器临时文件
  assert.equal(isIgnoredLocalPath('/docs/a.tmp'), true);
  assert.equal(isIgnoredLocalPath('/docs/a.swp'), true);
  assert.equal(isIgnoredLocalPath('/docs/a.lock'), true);
  // 正常文件不受影响
  assert.equal(isIgnoredLocalPath('/docs/客户对象导出结果.xlsx'), false);
  assert.equal(isIgnoredLocalPath('/docs/report.docx'), false);
  assert.equal(isIgnoredLocalPath('/'), false);
});

test('isConflictCopyName 只匹配机器格式冲突副本', () => {
  assert.equal(isConflictCopyName('template920 (本地-20260815141555).zip'), true);
  assert.equal(isConflictCopyName('template920 (冲突-20260815141555).zip'), true);
  assert.equal(isConflictCopyName('template920 (冲突-20260815141555-1).zip'), true);
  assert.equal(isConflictCopyName('template920.zip'), false);
  assert.equal(isConflictCopyName('报告 (本地-2026).docx'), false);
  assert.equal(isConflictCopyName('template920 (本地).zip'), false);
});

test('uniqueConflictName 同秒冲突追加序号，避免互相覆盖', () => {
  const exists = new Set<string>(['E:\\sync\\a.txt']);
  const first = uniqueConflictName('E:\\sync\\a.txt', '冲突', (p) => exists.has(p));
  exists.add(first);
  const second = uniqueConflictName('E:\\sync\\a.txt', '冲突', (p) => exists.has(p));
  assert.notEqual(first, second);
  assert.match(first, /\(冲突-\d{14}\)\.txt$/);
  assert.match(second, /\(冲突-\d{14}-\d+\)\.txt$/);
});

test('isLocallyChanged 以 sync_state.local_mtime 为准', () => {
  assert.equal(isLocallyChanged(null, 1000), true);
  assert.equal(isLocallyChanged({ localMtime: undefined }, 1000), true);
  assert.equal(isLocallyChanged({ localMtime: 1000 }, 1000), false);
  assert.equal(isLocallyChanged({ localMtime: 1000 }, 1001), true);
  assert.equal(isLocallyChanged({ localMtime: 2000 }, 1000), false);
});

test('conflictRelPath 推导冲突副本的同步相对路径', () => {
  assert.equal(
    conflictRelPath('/template920.zip', 'E:\\sync\\template920 (冲突-20260815141555).zip', 'E:\\sync'),
    '/template920 (冲突-20260815141555).zip',
  );
  assert.equal(
    conflictRelPath('/sub/template920.zip', 'E:\\sync\\sub\\template920 (冲突-20260815141555).zip', 'E:\\sync'),
    '/sub/template920 (冲突-20260815141555).zip',
  );
});
