const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { createDesktopRuntime } = require('./desktop-runtime.cjs');
const authFile = path.join(__dirname, 'auth-storage.json');
const scenarioFile = path.join(__dirname, 'sync-scenario.json');
const mode = process.argv[2] || 'setup';
const runtime = createDesktopRuntime(path.join(__dirname, 'desktop-data'), (log) => console.log(JSON.stringify({ syncLog:log })));
const apiModule = runtime.load('api-client.ts');
const saved = JSON.parse(JSON.parse(fs.readFileSync(authFile, 'utf8'))['stcloud:auth']);
apiModule.setAuth(saved.token, saved.refreshToken, saved.sessionId);
const client = apiModule.apiClient;
const db = runtime.load('database.ts');
const { SyncEngine } = runtime.load('sync-engine.ts');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function check(value, message) { assert.ok(value, message); }
async function api(method, url, data) { const response = method === 'get' ? await client.get(url, { params:data }) : await client[method](url, data); check(response.data.code === 200, JSON.stringify({ url, code:response.data.code, message:response.data.message })); return response.data.data; }
async function waitDelta(rootId, since, type, nodeId) {
  for (let i=0;i<20;i++) { const delta = await api('get', `/sync/roots/${rootId}/delta`, { since }); if (delta.changes.some((c) => c.changeType === type && c.nodeId === nodeId)) return delta; await sleep(500); }
  throw new Error('指定真实 journal 事件未在 10s 内出现: ' + type);
}
async function waitUpload(taskId) {
  for (let i=0;i<60;i++) { const task = db.getTask(taskId); if (task?.status === 'failed') throw new Error('真实上传失败: ' + task.error); if (task?.status === 'completed') return task; await sleep(500); }
  throw new Error('真实上传等待超时');
}
async function main() {
  await db.initDatabase();
  if (mode === 'retry') {
    await managerRound(JSON.parse(fs.readFileSync(scenarioFile, 'utf8')));
    return;
  }
  if (mode === 'restart') {
    const s = JSON.parse(fs.readFileSync(scenarioFile, 'utf8'));
    const before = db.getSyncConfig(s.rootId);
    const priorState = db.getSyncState(s.rootId, '/note.txt');
    check(priorState?.nodeId === s.nodeId, '新进程必须从真实 SQLite 读到旧映射');
    const engine = new SyncEngine(s.root);
    try {
      await engine.start();
      await sleep(1200);
      const current = db.getSyncState(s.rootId, '/destination/renamed.txt');
      check(current?.nodeId === s.nodeId, '新路径映射保持同一个节点');
      check(fs.readFileSync(path.join(s.root.localPath, 'destination/renamed.txt'), 'utf8') === s.updatedBytes, '离线 MOVE RENAME UPDATE 后当前字节一致');
      check(!fs.existsSync(path.join(s.root.localPath, 'note.txt')), '旧路径退出镜像');
      check(!db.getSyncState(s.rootId, '/note.txt'), '旧映射清除');
      check(BigInt(db.getSyncConfig(s.rootId).cursor) > BigInt(before.cursor), '重启后游标推进');
      console.log(JSON.stringify({ check:'fresh_process_move_rename_update', oldCursor:before.cursor, cursor:db.getSyncConfig(s.rootId).cursor, nodeId:s.nodeId, sqliteRestored:true, bytesMatch:true }));
    } finally { await engine.stop(); }
    return;
  }
  const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixture.json'), 'utf8'));
  let folder, destination, root, bytes, nodeId;
  if (fs.existsSync(scenarioFile)) {
    const prior = JSON.parse(fs.readFileSync(scenarioFile, 'utf8'));
    folder = { id:prior.folderId }; destination = { id:prior.destinationId }; root = prior.root; bytes = prior.originalBytes; nodeId = prior.nodeId;
  } else {
    folder = await api('post', '/file/folder', { parentId:'0', folderName:'env-sync-' + Date.now().toString(36) });
    destination = await api('post', '/file/folder', { parentId:folder.id, folderName:'destination' });
    const localPath = path.join(__dirname, 'sync-root-' + folder.id);
    const syncRoot = await api('post', '/sync/roots', { cloudFolderNodeId:folder.id, localPathHint:localPath });
    root = { rootId:syncRoot.id, cloudFolderNodeId:folder.id, localPath };
    db.upsertSyncConfig({ rootId:root.rootId, localPath, cursor:'0', status:'active', userId:fixture.userId });
    bytes = 'Original dedicated sync bytes ' + require('node:crypto').randomUUID();
    const sourcePath = path.join(__dirname, 'note.txt');
    fs.writeFileSync(sourcePath, bytes);
    const upload = runtime.load('upload-manager.ts');
    const taskId = await upload.startUpload(sourcePath, folder.id);
    const task = await waitUpload(taskId);
    nodeId = task.fileId;
    fs.writeFileSync(scenarioFile, JSON.stringify({ root, rootId:root.rootId, folderId:folder.id, destinationId:destination.id, nodeId, originalBytes:bytes, createdAt:new Date().toISOString() }, null, 2));
  }
  const localPath = root.localPath;
  if (mode === 'replay') {
    // 只用已注册本轮 fixture 恢复场景起点；保持版本/历史/SQLite，禁止清库掩盖恢复问题。
    const current = await api('get', `/file/${nodeId}`);
    if (current.name !== 'note.txt') await api('put', `/file/${nodeId}/rename`, { newName:'note.txt' });
    if (current.parentId !== folder.id) await api('post', '/file/move', { nodeIds:[nodeId], targetParentId:folder.id });
    await api('put', `/file/${nodeId}/text-content`, { content:bytes });
    await waitDelta(root.rootId, db.getSyncConfig(root.rootId).cursor, 'UPDATE', nodeId);
  }
  check(typeof nodeId === 'string', '上传节点 ID 保持字符串');
  console.log(JSON.stringify({ check:'actual_desktop_upload_to_s3_completed', nodeId, rootId:root.rootId, folderId:folder.id, userId:fixture.userId }));
  const engine = new SyncEngine(root);
  let s;
  try {
    await engine.start();
    await sleep(1000);
    check(fs.readFileSync(path.join(localPath, 'note.txt'), 'utf8') === bytes, '真实 S3 初次下载一致');
    check(db.getSyncState(root.rootId, '/note.txt')?.nodeId === nodeId, '初次映射一致');
    const oldCursor = db.getSyncConfig(root.rootId).cursor;
    await engine.stop();
    await api('post', '/file/delete', { nodeIds:[nodeId] });
    const deleted = await waitDelta(root.rootId, oldCursor, 'DELETE', nodeId);
    const oldDelete = deleted.changes.find((c) => c.changeType === 'DELETE' && c.nodeId === nodeId);
    await api('post', '/recycle/restore', { nodeIds:[nodeId] });
    await waitDelta(root.rootId, oldCursor, 'CREATE', nodeId);
    // 使用真实服务产生并保存的旧 DELETE，受控重放避免依赖推送到达顺序。
    await engine.processCloudDelta([oldDelete]);
    check(fs.readFileSync(path.join(localPath, 'note.txt'), 'utf8') === bytes, '恢复后旧 DELETE 不得损坏字节');
    check(db.getSyncState(root.rootId, '/note.txt')?.nodeId === nodeId, '恢复后旧 DELETE 保留映射');
    await engine.start();
    check(BigInt(db.getSyncConfig(root.rootId).cursor) > BigInt(oldCursor), '真实增量继续推进');
    console.log(JSON.stringify({ check:'real_delete_restore_old_delete_replay', oldDeleteLogId:oldDelete.logId, cursor:db.getSyncConfig(root.rootId).cursor, bytesPreserved:true, mappingPreserved:true }));
    await engine.stop();
    await api('put', `/file/${nodeId}/rename`, { newName:'renamed.txt' });
    await api('post', '/file/move', { nodeIds:[nodeId], targetParentId:destination.id });
    const updatedBytes = 'Updated dedicated bytes after offline rename move ' + require('node:crypto').randomUUID();
    await api('put', `/file/${nodeId}/text-content`, { content:updatedBytes });
    await waitDelta(root.rootId, db.getSyncConfig(root.rootId).cursor, 'UPDATE', nodeId);
    s = { root, rootId:root.rootId, folderId:folder.id, destinationId:destination.id, nodeId, originalBytes:bytes, updatedBytes, oldDelete, createdAt:new Date().toISOString() };
    fs.writeFileSync(scenarioFile, JSON.stringify(s, null, 2));
  } finally { await engine.stop(); }
  const restarted = spawnSync(process.execPath, [__filename, 'restart'], { encoding:'utf8', timeout:60000 });
  process.stdout.write(restarted.stdout);
  check(restarted.status === 0, '新进程同步验证失败: ' + restarted.stderr.slice(0, 300));
  // 子进程更新了真实磁盘库，主进程重新读盘，避免测试宿主用旧内存副本覆盖最新映射。
  await db.initDatabase();
  await managerRound(s);
}
async function managerRound(s) {
  const manager = runtime.load('sync-manager.ts');
  const originalGet = client.get;
  client.get = async (url, ...args) => { if (url.endsWith('/exclusions')) throw new Error('受控启动网络故障'); return originalGet.call(client, url, ...args); };
  await assert.rejects(manager.startSync(s.rootId, s.folderId, s.root.localPath), /受控启动网络故障/);
  check(!manager.isSyncing(s.rootId), '失败启动必须清理实例');
  client.get = originalGet;
  await manager.startSync(s.rootId, s.folderId, s.root.localPath);
  check(manager.isSyncing(s.rootId), '清理后可实际重试启动');
  await sleep(1200);
  const localUpdate = s.updatedBytes + ' + actual chokidar local update ' + require('node:crypto').randomUUID();
  fs.writeFileSync(path.join(s.root.localPath, 'destination/renamed.txt'), localUpdate);
  const localMd5 = require('node:crypto').createHash('md5').update(localUpdate).digest('hex');
  let uploaded = false;
  for (let i=0;i<40;i++) { const detail = await api('get', `/file/${s.nodeId}`); const state = db.getSyncState(s.rootId, '/destination/renamed.txt'); if (detail.status === 0 && detail.fileMd5 === localMd5 && state?.md5 === localMd5) { uploaded=true; break; } await sleep(500); }
  check(uploaded, '真实 chokidar 本地修改须通过实际桌面上传进入 S3/后端');
  const downloaded = await client.get(`/file/${s.nodeId}/stream`, { responseType:'arraybuffer' });
  const downloadedMd5 = require('node:crypto').createHash('md5').update(Buffer.from(downloaded.data)).digest('hex');
  check(downloadedMd5 === localMd5, '本地修改后的 S3 下载字节匹配: ' + JSON.stringify({ expectedMd5:localMd5, actualMd5:downloadedMd5 }));
  console.log(JSON.stringify({ check:'actual_chokidar_local_update_upload', nodeId:s.nodeId, bytesMatch:true }));
  await manager.stopAllSync();
  fs.writeFileSync(path.join(__dirname,'sync-source-manifest.json'), JSON.stringify({ executedAt:new Date().toISOString(), process:process.pid, backend:'http://127.0.0.1:8080', files:runtime.sourceManifest() }, null, 2));
  console.log(JSON.stringify({ check:'manager_failed_start_then_real_retry', faultInjection:'exclusions network failure', cleaned:true, retryRunning:true }));
  console.log(JSON.stringify({ check:'SYNC01_PASS', realBackend:true, sqljs:true, chokidar:true, s3Bytes:true, electronHostStub:true }));
}
main().catch((error) => { console.log(JSON.stringify({ failed:String(error.message).replace(/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_TOKEN]') })); process.exit(1); });
