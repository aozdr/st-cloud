const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function fixture(t, scenario = 'delete') {
  // Windows原生监听要求一致的长路径；系统TEMP可能是ADMINI~1短路径。
  const tempRoot = scenario.startsWith('watcher-') && process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA, 'Temp') : os.tmpdir();
  const directory = fs.mkdtempSync(path.join(tempRoot, 'st-restart-test-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(tempRoot));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const run = mode => {
    const result = spawnSync(process.execPath, [path.join(__dirname, 'testing/restart-worker.cjs'), directory, mode, scenario],
      { encoding: 'utf8', timeout: 20000, windowsHide: true });
    if (result.error) throw result.error;
    if (mode.startsWith('crash-')) {
      assert.equal(result.status, mode === 'crash-manifest' ? 73 : mode === 'crash-cursor' ? 74 : 75, result.stderr);
      return;
    }
    assert.equal(result.status, 0, result.stderr);
    return JSON.parse(result.stdout);
  };
  run.directory = directory;
  return run;
}
function preservedOther(before, after) {
  assert.deepEqual(after.otherConfig, before.otherConfig);
  assert.deepEqual(after.otherState, before.otherState);
  assert.equal(after.otherBytes, 'original-B');
}

for (const scenario of ['watcher-move', 'watcher-delete']) {
  test(`TC04-31: ${scenario} 云端故障期间用户修改由下一轮补传`, t => {
    const run = fixture(t, scenario);
    const result = run('fail-watcher');
    assert.equal(result.watcherFailureSnapshot.failed, true);
    assert.equal(result.watcherFailureSnapshot.source, 'user-edited');
    assert.equal(result.watcherFailureSnapshot.cloudUploads, 0);
    assert.equal(result.watcherFailureSnapshot.cursor, '9007199254740999');
    assert.equal(result.requests.some(row => row.url === '/file/delete'), false);
    assert.equal(result.cloudUploads.length, 1);
    assert.equal(result.cloudUploads[0].bytes, 'user-edited');
    assert.equal(result.tasks.at(0).status, 'completed');
  });
  test(`TC04-31: ${scenario} 真实chokidar自激不回传，独立用户编辑仍上传`, t => {
    const run = fixture(t, scenario);
    const result = run('resume');
    assert.equal(result.cursor, '9007199254740999');
    assert.ok(result.watcherEvents.some(event => event.type === 'unlink' && event.relativePath === 'note.txt'));
    assert.equal(result.watcherInitialRequests.some(request => request.method === 'POST'), false);
    assert.equal(result.requests.some(request => request.url === '/file/delete'), false);
    assert.equal(result.cloudUploads.length, 1);
    assert.equal(result.cloudUploads[0].bytes, 'user-edited');
    assert.equal(result.cloudUploads[0].replaceFileId, scenario === 'watcher-move' ? '9007199254741003' : undefined);
    assert.equal(result.logs.some(row => row.type === 'error'), false);
    assert.equal(result.otherBytes, 'original-B');
  });
}

test('TC04-28: 成功冲突后同页失败，跨进程重放不回传覆盖原云端版本', t => {
  const run = fixture(t, 'conflict-replay');
  const before = run('fail-second');
  assert.equal(before.cursor, '9007199254740997');
  assert.equal(before.cloudUploads.length, 1);
  assert.equal(before.source, 'original-A');
  assert.ok(Object.values(before.tree).includes('cloud-v2'));
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.cloudUploads.length, 1, '成功的keep_both重放不应上传原文件覆盖云端');
  assert.equal(after.requests.some(row => row.body?.replaceFileId), false);
  assert.equal(after.source, 'original-A');
  assert.equal(after.second, 'cloud-v2');
  preservedOther(before, after);
});

for (const mode of ['fail-upload', 'fail-download']) {
  test(`TC04-28: ${mode} 连续三轮部分成功不重复已完成副本`, t => {
    const run = fixture(t, 'conflict-keep_both');
    let result;
    for (let attempt = 0; attempt < 3; attempt++) {
      result = run(mode);
      assert.equal(result.cursor, '9007199254740997');
      assert.equal(result.source, 'original-A');
    }
    const cloudCopies = Object.entries(result.tree).filter(([name, bytes]) => name.includes('(冲突-') && bytes === 'cloud-v2');
    assert.equal(mode === 'fail-upload' ? cloudCopies.length : result.cloudUploads.length, 1,
      '同一内容冲突的已完成副本必须复用，不能每次重试新增');
    const recovered = run('resume');
    assert.equal(recovered.cursor, '9007199254740999');
    assert.equal(recovered.cloudUploads.length, 1);
    assert.equal(Object.entries(recovered.tree).filter(([name, bytes]) => name.includes('(冲突-') && bytes === 'cloud-v2').length, 1);
  });
}

test('TC04-28: 已存云端副本详情失败不假定完成，恢复后复用而非重新上传', t => {
  const run = fixture(t, 'conflict-keep_both');
  const first = run('fail-download');
  assert.equal(first.cloudUploads.length, 1);
  const failed = run('fail-copy-detail');
  assert.equal(failed.cursor, '9007199254740997');
  assert.equal(failed.source, 'original-A');
  assert.equal(failed.cloudUploads.length, 1);
  assert.ok(failed.logs.some(row => row.type === 'error' && row.message.includes('saved copy')));
  const recovered = run('resume');
  assert.equal(recovered.cursor, '9007199254740999');
  assert.equal(recovered.cloudUploads.length, 1);
});

test('TC04-28: 云端保留副本已被修改时重新保全当前本地字节', t => {
  const run = fixture(t, 'conflict-keep_both');
  const first = run('fail-download');
  const copies = first.cloudUploads;
  copies[0].bytes = 'changed-by-user';
  copies[0].fileMd5 = require('node:crypto').createHash('md5').update(copies[0].bytes).digest('hex');
  fs.writeFileSync(path.join(run.directory, 'cloud-uploads.json'), JSON.stringify(copies));
  const recovered = run('resume');
  assert.equal(recovered.cursor, '9007199254740999');
  assert.equal(recovered.cloudUploads.length, 2);
  assert.deepEqual(recovered.cloudUploads.map(row => row.bytes), ['changed-by-user', 'original-A']);
});

for (const strategy of ['keep_both', 'local_wins', 'latest_wins']) {
  for (const mode of ['fail-upload', 'fail-timeout']) {
    test(`TC04-24/25: ${strategy}/${mode} 真实上传任务失败保留基线和cursor`, t => {
      const run = fixture(t, 'conflict-' + strategy);
      const initial = run('inspect');
      const failed = run(mode);
      assert.equal(failed.cursor, initial.cursor);
      assert.equal(failed.source, 'original-A');
      for (const key of ['nodeId', 'md5', 'localMtime']) assert.equal(failed.state[key], initial.state[key]);
      assert.ok(failed.tasks.length > 0);
      assert.equal(failed.tasks.at(0).status, mode === 'fail-timeout' ? 'merging' : 'failed');
      assert.equal(failed.tasks.at(0).sourceBytes, 'original-A');
      assert.equal(failed.cloudUploads.length, 0);
      if (strategy === 'keep_both') {
        assert.ok(Object.values(failed.tree).includes('cloud-v2'));
        assert.ok(failed.history.some(row => row.action === 'conflict' && row.status === 'error'));
      }
      preservedOther(initial, failed);
      const recovered = run('resume');
      assert.equal(recovered.cursor, '9007199254740999');
      assert.equal(recovered.source, 'original-A');
      assert.ok(recovered.cloudUploads.some(row => row.bytes === 'original-A'));
      assert.equal(recovered.logs.some(row => row.type === 'error'), false);
      preservedOther(initial, recovered);
    });
  }
}

for (const mode of ['fail-download', 'fail-upload', 'fail-both']) {
  for (const scenario of ['conflict-keep_both', 'conflict-reconcile-baseline', 'conflict-reconcile-other', 'conflict-reconcile-missing']) {
    test(`TC04-26/27: ${scenario}/${mode} 连续失败重启保持原身份与MD5`, t => {
      const run = fixture(t, scenario);
      const initial = run('inspect');
      for (let attempt = 0; attempt < 2; attempt++) {
        const failed = run(mode);
        assert.equal(failed.cursor, initial.cursor);
        assert.equal(failed.source, 'original-A');
        for (const key of ['nodeId', 'md5', 'localMtime']) assert.equal(failed.state?.[key], initial.state?.[key]);
        if (scenario.includes('reconcile')) assert.equal(failed.rootConfig.syncVersion, 1);
        assert.ok(failed.history.some(row => row.action === 'conflict' && row.status === 'error'));
        const downloaded = failed.requests.some(row => row.url === '/file/' + '9007199254741003' + '/stream');
        if (mode === 'fail-upload') {
          assert.equal(downloaded, attempt === 0, '已完整保全的云端版在后续重试不重复下载');
          assert.equal(Object.values(failed.tree).filter(bytes => bytes === 'cloud-v2').length, 1);
        } else {
          assert.ok(downloaded, '未完成的云端副本必须继续尝试下载');
        }
        preservedOther(initial, failed);
      }
      const recovered = run('resume');
      assert.equal(recovered.cursor, '9007199254740999');
      assert.equal(recovered.source, 'original-A');
      assert.ok(Object.values(recovered.tree).includes('cloud-v2'));
      assert.ok(recovered.cloudUploads.some(row => row.bytes === 'original-A'));
      assert.equal(recovered.logs.some(row => row.type === 'error'), false);
      preservedOther(initial, recovered);
    });
  }
}

for (const scenario of ['reconcile-root-rename', 'reconcile-root-move', 'reconcile-root-missing-old']) {
  for (const failure of ['fail-root-list', 'fail-root-detail']) {
    test(`TC04-09: ${scenario}/${failure} 对账失败后跨进程恢复且根身份不变`, t => {
      const run = fixture(t, scenario);
      const before = run(failure);
      assert.equal(before.cursor, '9007199254740997');
      assert.equal(before.rootExists, true);
      assert.equal(before.source, 'original-A');
      assert.ok(before.logs.some(row => row.type === 'error' && row.message.includes('全量对账失败')));
      const after = run('resume');
      assert.equal(after.cursor, '9007199254740999');
      assert.equal(after.rootExists, true);
      assert.equal(after.rootConfig.rootId, before.rootConfig.rootId);
      assert.equal(after.rootConfig.localPath, before.rootConfig.localPath);
      assert.equal(after.renamed, 'cloud-v2');
      assert.equal(after.states.find(row => row.localPath === '/renamed.txt').nodeId, '9007199254741003');
      assert.equal(after.state, null);
      assert.equal(after.source, null);
      assert.ok(Object.entries(after.allRecovery).some(([name, bytes]) => name.endsWith('/files/note.txt') && bytes === 'original-A'));
      assert.ok(after.requests.filter(row => row.url === '/file/list').every(row => row.parentId === 'folder'));
      assert.equal(after.logs.some(row => row.type === 'error'), false);
      if (failure === 'fail-root-detail') assert.equal(after.requests.some(row => row.url.endsWith('/stream')), false);
      preservedOther(before, after);
    });
  }
}

test('TC04-20: 旧版启动详情失败保留版本，重启升级保全修改和无状态文件', t => {
  const run = fixture(t, 'upgrade');
  const before = run('fail-upgrade-detail');
  assert.equal(before.rootConfig.syncVersion, 1);
  assert.equal(before.cursor, '9007199254740997');
  assert.equal(before.source, 'modified-local');
  assert.equal(before.unknown, 'untracked-local');
  assert.equal(before.states.find(row => row.localPath === '/unknown.txt').status, 'needs_review');
  assert.ok(before.logs.some(row => row.type === 'error' && row.message.includes('全量对账未完整成功')));
  const after = run('resume');
  assert.equal(after.rootConfig.syncVersion, 4);
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.source, null);
  assert.equal(after.state, null);
  assert.equal(after.unknown, 'untracked-local');
  assert.equal(after.states.find(row => row.localPath === '/unknown.txt').status, 'needs_review');
  assert.ok(after.logs.some(row => row.type === 'conflict' && row.message.includes('人工核对')));
  assert.ok(Object.entries(after.allRecovery).some(([name, bytes]) => name.endsWith('/files/note.txt') && bytes === 'modified-local'));
  assert.equal(after.logs.some(row => row.type === 'error'), false);
  preservedOther(before, after);
});

for (const failure of ['fail-root-A', 'fail-root-B']) {
  test(`TC04-03: A迁移到B/${failure} 两根独立提交且跨进程恢复`, t => {
    const run = fixture(t, 'cross-root');
    const initial = run('inspect');
    const before = run(failure);
    assert.equal(before.otherBytes, 'original-B');
    assert.deepEqual(before.otherState, initial.otherState);
    assert.equal(before.cursor, failure === 'fail-root-A' ? '9007199254740997' : '9007199254740999');
    assert.equal(before.otherConfig.cursor, failure === 'fail-root-B' ? '9223372036854775806' : '9223372036854775807');
    if (failure === 'fail-root-A') {
      assert.deepEqual(before.states, initial.states);
      assert.equal(before.source, 'original-A');
      assert.equal(before.otherNote, 'original-A');
    } else {
      assert.deepEqual(before.otherStates, initial.otherStates);
      assert.equal(before.otherNote, null);
      assert.equal(before.saved, 'original-A');
    }
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.otherConfig.cursor, '9223372036854775807');
    assert.equal(after.source, null);
    assert.equal(after.saved, 'original-A');
    assert.equal(after.otherNote, 'original-A');
    assert.equal(after.otherStates.find(row => row.localPath === '/note.txt').nodeId, '9007199254741003');
    assert.deepEqual(after.otherState, initial.otherState);
    assert.equal(after.otherBytes, 'original-B');
    assert.equal(after.logs.some(row => row.type === 'error'), false);
  });
}

for (const kind of ['CREATE', 'MOVE']) {
  for (const destination of ['inside', 'outside', 'trash']) {
    test(`TC04-12: 历史${kind}/${destination} 按当前树恢复且详情403不被当作删除`, t => {
      const run = fixture(t, `stale-${kind}-${destination}`);
      const before = run('fail-stale-forbidden');
      assert.equal(before.cursor, '9007199254740997');
      assert.equal(before.source, 'original-A');
      assert.equal(before.state.nodeId, '9007199254741003');
      assert.deepEqual(before.allRecovery, {});
      assert.ok(before.logs.some(row => row.type === 'error'));
      const after = run('resume');
      assert.equal(after.cursor, '9007199254740999');
      assert.equal(after.source, null);
      assert.equal(after.state, null);
      assert.equal(after.renamed, destination === 'inside' ? 'cloud-v2' : null);
      assert.ok(Object.entries(after.allRecovery).some(([name, bytes]) => name.endsWith('/files/note.txt') && bytes === 'original-A'));
      assert.equal(after.logs.some(row => row.type === 'error'), false);
      preservedOther(before, after);
    });
  }
  test(`TC04-12: 历史${kind}详情超时保留旧状态和游标`, t => {
    const run = fixture(t, `stale-${kind}-inside`);
    const before = run('fail-stale-timeout');
    assert.equal(before.cursor, '9007199254740997');
    assert.equal(before.source, 'original-A');
    assert.equal(before.state.nodeId, '9007199254741003');
    assert.deepEqual(before.allRecovery, {});
    assert.ok(before.logs.some(row => row.type === 'error' && row.message.includes('timeout')));
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.renamed, 'cloud-v2');
    preservedOther(before, after);
  });
}

test('TC04-29: 清单提交前退出，重启完成 pending 后才提交游标', t => {
  const run = fixture(t);
  run('crash-manifest');
  const before = run('inspect');
  assert.equal(before.cursor, '9007199254740997');
  assert.equal(before.source, null);
  assert.equal(before.saved, 'original-A');
  assert.equal(before.manifest.status, 'pending');
  const after = run('resume');
  assert.equal(after.manifest.status, 'complete');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.saved, 'original-A');
  assert.equal(after.state, null);
  preservedOther(before, after);
});

for (const mode of ['manifest-write', 'source-move']) {
  test(`TC04-29: ${mode} 故障后跨进程重试保全原件`, t => {
    const run = fixture(t);
    const before = run(mode);
    assert.equal(before.cursor, '9007199254740997');
    assert.equal(before.source, 'original-A');
    assert.ok(before.logs.some(entry => entry.type === 'error'));
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.manifest.status, 'complete');
    assert.equal(after.saved, 'original-A');
    preservedOther(before, after);
  });
}

test('TC04-32: 删除完成但游标提交前退出，跨进程重放不重复副作用', t => {
  const run = fixture(t);
  run('crash-cursor');
  const before = run('inspect');
  assert.equal(before.cursor, '9007199254740997');
  assert.equal(before.manifest.status, 'complete');
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.state, null);
  assert.equal(after.saved, 'original-A');
  assert.deepEqual(after.manifest, before.manifest);
  assert.deepEqual(after.history, before.history);
  preservedOther(before, after);
});

for (const scenario of ['reconcile', 'historical']) {
  test(`TC04-29: ${scenario} 清理进程退出后先完成清单再删除旧状态`, t => {
    const run = fixture(t, scenario);
    run('crash-manifest');
    const before = run('inspect');
    assert.equal(before.manifest.status, 'pending');
    assert.equal(before.cursor, '9007199254740997');
    const after = run('resume');
    assert.equal(after.manifest.status, 'complete');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.state, null);
    assert.equal(after.saved, 'original-A');
    preservedOther(before, after);
  });
}

test('TC04-29: complete 写入前退出，丢失恢复副本时拒绝确认游标', t => {
  const run = fixture(t);
  run('crash-complete-write');
  const before = run('inspect');
  assert.equal(before.manifest.status, 'pending');
  const saved = path.resolve(run.directory, 'userData/sync-recovery/9007199254740993/9007199254741001/files/note.txt');
  assert.ok(saved.startsWith(path.resolve(run.directory) + path.sep));
  fs.unlinkSync(saved);
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740997');
  assert.equal(after.manifest.status, 'pending');
  assert.deepEqual(after.state, before.state);
  assert.ok(after.logs.some(entry => entry.type === 'error' && /恢复副本缺失/.test(entry.message)));
  preservedOther(before, after);
});

test('TC04-29: complete 写入前退出，完整副本可在新进程恢复', t => {
  const run = fixture(t);
  run('crash-complete-write');
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.manifest.status, 'complete');
  assert.equal(after.saved, 'original-A');
});

for (const scenario of ['move', 'download', 'create']) {
  test(`TC04-32: ${scenario} 完成而游标未提交，重启重放不再次修改文件`, t => {
    const run = fixture(t, scenario);
    run('crash-cursor');
    const before = run('inspect');
    assert.equal(before.cursor, '9007199254740997');
    assert.equal(before.target.bytes, scenario === 'move' ? 'original-A' : 'cloud-v2');
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.deepEqual(after.target, before.target);
    assert.deepEqual(after.states, before.states);
    assert.deepEqual(after.history, before.history);
    assert.equal(after.requests.some(request => request.url.endsWith('/stream')), false);
    preservedOther(before, after);
  });
}

test('TC04-07: 同页第二事件失败，重启重放首事件不重复下载', t => {
  const run = fixture(t, 'two-events');
  const before = run('fail-second');
  assert.equal(before.cursor, '9007199254740997');
  assert.equal(before.target.bytes, 'cloud-v2');
  assert.equal(before.second, null);
  const firstState = before.states.find(row => row.localPath === '/note.txt');
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.second, 'cloud-v2');
  assert.deepEqual(after.target, before.target);
  assert.deepEqual(after.states.find(row => row.localPath === '/note.txt'), firstState);
  assert.equal(after.requests.some(request => request.url === '/file/9007199254741003/stream'), false);
  preservedOther(before, after);
});

for (const paging of ['same-page', 'cross-page']) {
  for (const size of ['same-size', 'different-size']) {
    test(`TC04-22: ${paging}/${size} 历史日志采用当前内容且只下载一次`, t => {
      const run = fixture(t, `history-${paging}-${size}`);
      const result = run('resume');
      assert.equal(result.cursor, '9007199254740999');
      assert.equal(result.target.bytes, 'cloud-v2');
      assert.equal(result.requests.filter(request => request.url.endsWith('/stream')).length, 1);
      assert.equal(result.history.filter(row => row.action === 'download' && row.status === 'success').length, 1);
      assert.equal(result.otherBytes, 'original-B');
    });
  }
}

for (const scenario of ['race-same', 'race-size', 'truncate', 'tamper']) {
  test(`TC04-23: ${scenario} 拒绝错误流，重启刷新元数据后再提交`, t => {
    const run = fixture(t, scenario);
    const before = run('fail-content');
    assert.equal(before.cursor, '9007199254740997');
    assert.equal(before.source, 'original-A');
    assert.equal(before.state.md5, 'old');
    assert.ok(before.history.some(row => row.status === 'error'));
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.target.bytes, scenario === 'race-size' ? 'cloud-v3-longer'
      : scenario === 'race-same' ? 'cloud-v3' : 'cloud-v2');
    preservedOther(before, after);
  });
}

for (const scenario of ['delete-returned', 'delete-reused']) {
  test(`TC04-13: ${scenario} 旧删除事件不能清理有效节点`, t => {
    const run = fixture(t, scenario);
    const result = run('resume');
    assert.equal(result.cursor, '9007199254740999');
    assert.equal(result.source, 'original-A');
    assert.equal(result.state.nodeId, scenario === 'delete-returned' ? '9007199254741003' : '9007199254741009');
    assert.equal(result.manifest, null);
    assert.equal(result.saved, null);
    assert.equal(result.history.length, 0);
    assert.equal(result.requests.some(request => request.url.endsWith('/stream')), false);
    assert.equal(result.otherBytes, 'original-B');
  });
}

test('TC04-19: 全量扫描将路径登记为新 X 后，旧 N 快照不能清理 X', t => {
  const run = fixture(t, 'reconcile-reused');
  const result = run('resume');
  assert.equal(result.cursor, '9007199254740999');
  assert.equal(result.source, 'original-A');
  assert.equal(result.state.nodeId, '9007199254741009');
  assert.equal(result.manifest, null);
  assert.equal(result.saved, null);
  assert.equal(result.history.length, 0);
  assert.equal(result.otherBytes, 'original-B');
});

for (const scenario of ['invalid-current-parent', 'invalid-current-drive', 'invalid-current-link',
  'invalid-old-parent', 'invalid-old-drive', 'invalid-old-link', 'invalid-recovery-link']) {
  test(`TC04-10: ${scenario} 完整引擎拒绝且根外哨兵不变`, t => {
    const run = fixture(t, scenario);
    const before = run('inspect');
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740997');
    assert.equal(after.source, 'original-A');
    assert.deepEqual(after.states, before.states);
    assert.equal(after.outside, 'outside-sentinel');
    assert.equal(after.outsideManifest, 'outside-manifest');
    assert.ok(after.logs.some(entry => entry.type === 'error' && /越界|非法|符号链接/.test(entry.message)));
    preservedOther(before, after);
  });
}

function completeFolder(result) {
  assert.equal(result.cursor, '9007199254740999');
  for (let index = 0; index < 101; index++) {
    assert.equal(result.tree[`newfolder/file-${String(index).padStart(3, '0')}.txt`], 'cloud-file-' + index);
  }
  assert.equal(result.tree['newfolder/nested/deep/leaf.txt'], 'deep-leaf');
  assert.equal(result.tree['newfolder/empty/'], 'directory');
  assert.equal(Object.keys(result.tree).some(key => key.startsWith('newfolder/excluded')), false);
  assert.equal(result.requests.some(request => request.parentId === '9200000000000004'), false);
  assert.equal(result.states.find(row => row.localPath === '/newfolder').status, 'synced');
  assert.equal(result.states.some(row => row.localPath.startsWith('/oldfolder')), false);
}

test('TC04-04: 外部目录移入完整下载 101 文件及深层/空目录，排除项不进入', t => {
  const run = fixture(t, 'folder-import');
  const result = run('resume');
  completeFolder(result);
  assert.equal(result.requests.filter(request => request.url.endsWith('/stream')).length, 102);
  assert.ok(result.requests.some(request => request.page === 2));
});

test('TC04-05: 移出目录不依赖父目录 mtime，已改文件与未知文件均保全', t => {
  const run = fixture(t, 'folder-export');
  const before = run('inspect');
  assert.equal(before.oldFolderMtime, before.states.find(row => row.localPath === '/oldfolder').localMtime);
  const result = run('resume');
  assert.equal(result.cursor, '9007199254740999');
  assert.deepEqual(result.recoveryTree, before.tree);
  assert.equal(result.recoveryTree['oldfolder/managed.txt'], 'edited-local');
  assert.equal(result.recoveryTree['oldfolder/unknown.txt'], 'unknown-local');
  assert.deepEqual(result.tree, {});
  assert.equal(result.states.length, 0);
  assert.equal(result.manifest.status, 'complete');
  preservedOther(before, result);
});

for (const scenario of ['folder-modified', 'folder-missing', 'folder-import']) {
  test(`TC04-${scenario === 'folder-modified' ? '17' : scenario === 'folder-import' ? '04' : '18'}: ${scenario} 第二页失败后跨进程补齐`, t => {
    const run = fixture(t, scenario);
    const before = run('fail-list');
    assert.equal(before.cursor, '9007199254740997');
    assert.equal(before.states.find(row => row.localPath === '/newfolder').status, scenario === 'folder-import' ? 'synced' : 'pending');
    assert.equal(before.tree['newfolder/file-096.txt'], 'cloud-file-96');
    assert.equal(before.tree['newfolder/file-100.txt'], undefined);
    if (scenario === 'folder-modified') {
      assert.equal(before.recoveryTree['oldfolder/managed.txt'], 'edited-local');
      assert.equal(before.recoveryTree['oldfolder/unknown.txt'], 'unknown-local');
    }
    const after = run('resume');
    completeFolder(after);
    assert.equal(after.requests.filter(request => request.url.endsWith('/stream')).length, 4);
    assert.deepEqual(after.recoveryTree, before.recoveryTree);
    preservedOther(before, after);
  });
}

test('TC04-18: 缺失源目录第二页失败，同一引擎重试识别 pending 目标', t => {
  const run = fixture(t, 'folder-missing');
  const result = run('retry-list');
  assert.equal(result.attempts[0].cursor, '9007199254740997');
  assert.equal(result.attempts[0].states.find(row => row.localPath === '/newfolder').status, 'pending');
  completeFolder(result);
  assert.equal(result.requests.filter(request => request.url.endsWith('/stream')).length, 102);
});

test('TC04-18: 源缺失但目标属于不同 X 时保留目标并拒绝推进', t => {
  const run = fixture(t, 'folder-collision');
  const before = run('inspect');
  const after = run('resume');
  assert.equal(after.cursor, '9007199254740997');
  assert.deepEqual(after.tree, before.tree);
  assert.deepEqual(after.states, before.states);
  assert.ok(after.logs.some(entry => /移动目标已存在/.test(entry.message)));
  preservedOther(before, after);
});
for (const kind of ['move', 'rename']) {
  for (const shape of ['present', 'missing', 'collision']) {
    test(`TC04-14: ${kind} ${shape} 实际目录基线与目标保护`, t => {
      const run = fixture(t, `${kind}-${shape}`);
      const before = run('inspect');
      const after = run('resume');
      preservedOther(before, after);
      if (shape === 'collision') {
        assert.equal(after.cursor, before.cursor);
        assert.equal(after.source, 'original-A');
        assert.equal(after.target.bytes, 'belongs-to-X');
        assert.deepEqual(after.states, before.states);
      } else {
        assert.equal(after.cursor, '9007199254740999');
        assert.equal(after.source, null);
        assert.equal(after.target.bytes, 'original-A');
        const moved = after.states.find(s => s.localPath === '/moved.txt');
        assert.equal(moved.nodeId, '9007199254741003');
        assert.equal(moved.md5, before.state.md5);
        assert.equal(moved.localMtime, shape === 'present' ? before.state.localMtime : after.target.mtime);
        assert.equal(after.states.some(s => s.localPath === '/note.txt'), false, '源缺失补齐后不能残留旧身份');
      }
      assert.equal(after.requests.some(r => r.method === 'POST'), false);
    });
  }
}
test('TC04-14: 未修改目录MOVE保留后代基线', t => {
  const run = fixture(t, 'folder-unchanged');
  const before = run('inspect'), after = run('resume');
  assert.equal(after.cursor, '9007199254740999');
  assert.equal(after.tree['newfolder/managed.txt'], 'old-local');
  assert.equal(after.tree['newfolder/unknown.txt'], 'unknown-local');
  for (const row of before.states) {
    const moved = after.states.find(s => s.localPath === row.localPath.replace('/oldfolder', '/newfolder'));
    assert.equal(moved.nodeId, row.nodeId);
    assert.equal(moved.localMtime, row.localMtime);
    assert.equal(moved.md5, row.md5);
  }
  assert.equal(after.states.some(s => s.localPath.startsWith('/oldfolder')), false);
  assert.equal(after.requests.some(r => r.method === 'POST'), false);
  preservedOther(before, after);
});
for (const identity of ['reused', 'unknown']) {
  test(`TC04-${identity === 'reused' ? '15' : '16'}: ${identity} 旧路径身份对账失败及跨进程恢复`, t => {
    const run = fixture(t, 'identity-' + identity);
    const before = run('inspect'), failed = run('fail-identity-list');
    assert.equal(failed.cursor, before.cursor);
    assert.equal(failed.source, 'original-A');
    assert.deepEqual(failed.states, before.states);
    preservedOther(before, failed);
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.source, 'original-A');
    assert.equal(after.tree['moved.txt'], 'cloud-v2');
    assert.equal(after.states.find(s => s.localPath === '/moved.txt').nodeId, '9007199254741003');
    if (identity === 'reused') {
      assert.equal(after.state.nodeId, '9007199254741009');
      assert.equal(after.state.md5, before.state.md5);
      assert.equal(after.state.localMtime, before.state.localMtime);
    } else assert.equal(after.state, null, '未知本地内容保持未绑定，不能冒认云端节点');
    assert.equal(after.requests.some(r => r.method === 'POST'), false);
    preservedOther(before, after);
  });
}
test('TC04-16: 直接消费同路径脏MOVE不能改绑X，失败保留基线', t => {
  const run = fixture(t, 'identity-same');
  const before = run('inspect'), after = run('fail-identity-list');
  assert.match(after.directError, /身份不匹配.*对账失败/);
  assert.equal(after.source, 'original-A');
  assert.deepEqual(after.states, before.states);
  assert.equal(after.state.nodeId, '9007199254741009');
  assert.equal(after.cursor, before.cursor);
  assert.ok(after.requests.some(r => r.url === '/file/list'));
  assert.equal(after.requests.some(r => r.method === 'POST'), false);
  preservedOther(before, after);
});

for (const kind of ['move', 'rename']) {
  test(`TC04-14: ${kind} 源缺失下载失败保留旧基线，再次成功后清理`, t => {
    const run = fixture(t, kind + '-missing');
    const before = run('inspect'), failed = run('fail-move-download');
    assert.equal(failed.cursor, before.cursor);
    assert.deepEqual(failed.states, before.states);
    assert.equal(failed.target, null);
    const after = run('resume');
    assert.equal(after.cursor, '9007199254740999');
    assert.equal(after.target.bytes, 'original-A');
    assert.equal(after.state, null);
    preservedOther(before, after);
  });
}

test('TC04-02: 排除子树真实watcher新建及改名不上传，普通文件仍上传', t => {
  const run = fixture(t, 'watcher-exclusion');
  const after = run('resume');
  assert.ok(after.watcherEvents.some(e => e.relativePath === 'excluded/deep/renamed.txt'));
  assert.equal(after.cloudUploads.length, 1);
  assert.equal(after.cloudUploads[0].bytes, 'user-edited');
  assert.equal(after.requests.some(r => r.url.endsWith('/stream')), false);
  assert.equal(after.requests.some(r => r.url === '/file/delete'), false);
  assert.equal(fs.readFileSync(path.join(run.directory, 'root/excluded/deep/renamed.txt'), 'utf8'), 'excluded-local');
  assert.equal(fs.existsSync(path.join(run.directory, 'root/excluded/deep/cloud.txt')), false);
  assert.equal(after.states.some(s => s.localPath.startsWith('/excluded')), false);
  assert.equal(after.logs.some(l => l.type === 'error'), false);
});
