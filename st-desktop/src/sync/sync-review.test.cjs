const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const vm = require('node:vm');
const { Readable } = require('node:stream');
const ts = require('typescript');
const hash = value => crypto.createHash('md5').update(value).digest('hex');
function load(file, dependencies, globals = {}) {
  const exports = {};
  const code = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8'), {
    compilerOptions: {module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020,esModuleInterop:true}
  }).outputText;
  vm.runInNewContext(code, {exports,Buffer,process,console,...globals,require(name) {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
    return dependencies[name];
  }});
  return exports;
}
function fixture(t, options = {}) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'st-review-fix-'));
  t.after(() => {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, {recursive:true,force:true});
  });
  const root = path.join(temporary, 'root'), userData = path.join(temporary, 'userData');
  fs.mkdirSync(root);fs.mkdirSync(userData);
  let config = {rootId:'R',localPath:root,cursor:'1',status:'active',syncVersion:options.upgrade?3:4,lastSyncAt:1};
  const states = new Map(), requests = [], logs = [], effects = {watcherStarts:0,watcherStops:0,timers:0,reconciliations:0};
  const database = {
    getSyncConfig:()=>config,upsertSyncConfig:row=>{config={...config,...Object.fromEntries(Object.entries(row).filter(([,v])=>v!==undefined))};},
    getSyncState:(_root,rel)=>states.get(rel),getAllSyncStates:()=>[...states.values()],
    upsertSyncState:row=>states.set(row.localPath,{...states.get(row.localPath),...row}),deleteSyncState:(_root,rel)=>states.delete(rel),
    getAllSyncConfigs:()=>[config],insertSyncHistory() {}
  };
  let changes = options.changes || [], broken = options.broken || false;
  const apiClient = {async get(url) {
    requests.push(url);
    if (url.endsWith('/exclusions')) {
      if (broken === 'exclusions') throw Error('exclusions offline');
      return {data:{code:200,data:[{relativePath:'/excluded.txt'}]}};
    }
    if (url.endsWith('/delta')) return {data:{data:{cursor:'2',hasMore:false,scopeProjectionVersion:2,changes}}};
    if (url === '/file/list') return {data:{code:200,data:{records:[],pages:0}}};
    if (url === '/file/ROOT') return {data:{code:200,data:{path:'/cloud/root',status:0,updatedAt:'2026-09-01'}}};
    if (url === '/file/N/stream') return {data:Readable.from(['bb'])};
    if (url === '/file/N') return options.detailCode
      ? {data:{code:options.detailCode,message:'fixture unavailable'}}
      : {data:{code:200,data:{path:options.outside?'/outside/excluded.txt':'/cloud/root/new.txt',status:0,fileSize:'2',fileMd5:hash('bb'),updatedAt:'2026-09-02'}}};
    throw Error('Unexpected URL '+url);
  }};
  const shared = {withRetry:fn=>fn(),syncLog:(_level,message)=>logs.push(message),emitSyncEvent() {},
    parseFileSize:value=>value==null?null:Number(value),SYNC_ENGINE_VERSION:4,ENGINE_WRITE_TTL_MS:30000};
  const recovery = load('sync/sync-recovery.ts',{fs,path,electron:{app:{getPath:()=>userData}},'../database':database});
  const utils = load('sync-utils.ts',{path});
  const md5 = {calculateFileMd5:async file=>hash(fs.readFileSync(file))};
  const download = load('sync/sync-download.ts',{fs,os,path,crypto,'../api-client':{apiClient},'../database':database,
    '../utils/md5':md5,'../sync-utils':utils,'./sync-shared':shared});
  const actualReconcile = load('sync/sync-reconcile.ts',{fs,path,crypto,'../api-client':{apiClient},'../database':database,
    '../utils/md5':md5,'./sync-shared':shared,'./sync-recovery':recovery});
  const reconcile = {...actualReconcile,async fullReconcile(ctx) {
    effects.reconciliations++;
    if (options.reconcileGate) await options.reconcileGate;
    return broken === 'reconcile'?false:actualReconcile.fullReconcile(ctx);
  }};
  const engineModule = load('sync-engine.ts',{fs,path,crypto,'./api-client':{apiClient},'./database':database,
    './file-watcher':{FileWatcher:class {setHandler() {} async start(){effects.watcherStarts++;} async stop(){effects.watcherStops++;}}},
    './utils/md5':md5,'./sync-retry':{},'./sync-utils':utils,'./sync/sync-shared':shared,
    './sync/sync-upload':{},'./sync/sync-download':download,'./sync/sync-reconcile':reconcile,'./sync/sync-recovery':recovery
  },{setInterval:callback=>{effects.timers++;effects.tick=callback;return 1;},clearInterval:()=>{effects.timers--;}});
  const engine = new engineModule.SyncEngine({rootId:'R',localPath:root,cloudFolderNodeId:'ROOT'});
  engine.scanLocalChanges=async()=>{};
  let authGeneration = 1;
  const assertAuthGeneration = expected => { if (expected !== authGeneration) throw Error('认证会话已变更'); };
  const manager = load('sync-manager.ts',{electron:{BrowserWindow:{getAllWindows:()=>[]}},'./api-client':{apiClient,
    captureAuthGeneration:()=>authGeneration, assertAuthGeneration,
    runWithAuthGeneration:(expected, action)=>{assertAuthGeneration(expected);return action();}},
    './sync-engine':engineModule,'./database':database,'./ws-client':{SyncWsClient:class {onChange(){}onStatus(){}start(){}stop(){}}}});
  t.after(()=>manager.stopAllSync());
  function local(rel, bytes, nodeId = 'N', changed = false) {
    const absolute = path.join(root,rel);fs.writeFileSync(absolute,bytes);
    states.set('/'+rel,{rootId:'R',localPath:'/'+rel,nodeId,md5:hash(changed?'a':bytes),size:1,
      localMtime:changed?1:fs.statSync(absolute).mtimeMs,status:'synced'});
  }
  return {root,userData,states,requests,logs,effects,engine,manager,local,config:()=>config,
    changeAuth:()=>{authGeneration++;},recover:()=>{broken=false;},changes:value=>{changes=value;}};
}
const move = type=>({logId:'2',nodeId:'N',nodeType:1,changeType:type,path:'/new.txt',oldPath:'/old.txt',size:1,md5:hash('a'),updatedAt:'2026-09-01'});

test('账号切换后旧引擎定时器收敛异常且不发送请求', async t => {
  const h = fixture(t);
  await h.manager.startSync('R','ROOT',h.root);
  const before = h.requests.length;
  h.changeAuth();
  h.effects.tick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.requests.length, before);
});

test('会话在启动对账期间变化，不提交同步版本或启动监听器', async t => {
  let release;
  const reconcileGate = new Promise(resolve => { release = resolve; });
  const h = fixture(t, {upgrade:true,reconcileGate});
  const starting = h.manager.startSync('R','ROOT',h.root);
  while (!h.effects.reconciliations) await new Promise(resolve => setImmediate(resolve));
  h.changeAuth(); release();
  await assert.rejects(starting, /认证会话已变更/);
  assert.equal(h.config().syncVersion, 3);
  assert.equal(h.effects.watcherStarts, 0);
  assert.equal(h.effects.timers, 0);
  assert.equal(h.manager.isSyncing('R'), false);
});

for (const code of [2008,403]) test('F3 DELETE status '+code,async t=>{
  const h=fixture(t,{detailCode:code,changes:[{...move('DELETE'),path:'/old.txt',oldPath:null}]});
  h.local('old.txt','local-edited');await h.engine.syncOnce();
  assert.equal(h.config().cursor,code===2008?'2':'1');
  assert.equal(fs.existsSync(path.join(h.root,'old.txt')),code===403);
  if(code===2008) {
    assert.equal(fs.readFileSync(path.join(h.userData,'sync-recovery/R/2/files/old.txt'),'utf8'),'local-edited');
    assert.equal(h.states.size,0);
  }
});
for (const type of ['MOVE','RENAME']) for(const source of ['missing','preserved','rename']) {
  test(`F4 ${type}/${source} 当前下载及本地旧基线`,async t=>{
    const h=fixture(t,{changes:[move(type)]});
    h.states.set('/old.txt',{rootId:'R',localPath:'/old.txt',nodeId:'N',size:1,md5:hash('a'),status:'synced'});
    if(source!=='missing')h.local('old.txt',source==='preserved'?'user-edited':'a','N',source==='preserved');
    await h.engine.syncOnce();assert.equal(h.config().cursor,'2');assert.equal(h.states.has('/old.txt'),false);
    assert.equal(fs.readFileSync(path.join(h.root,'new.txt'),'utf8'),source==='rename'?'a':'bb');
    assert.equal(h.states.get('/new.txt').md5,hash(source==='rename'?'a':'bb'));
    if(source==='rename') {
      h.changes([{...move('UPDATE'),oldPath:null}]);await h.engine.syncOnce();
      assert.equal(fs.readFileSync(path.join(h.root,'new.txt'),'utf8'),'bb');
    } else if(source==='preserved') {
      assert.equal(fs.readFileSync(path.join(h.userData,'sync-recovery/R/2/files/old.txt'),'utf8'),'user-edited');
    }
  });
}
test('F6 完成目标重放清理同节点旧映射',async t=>{
  const h=fixture(t,{changes:[move('MOVE')]});h.local('new.txt','bb');
  h.states.set('/old.txt',{rootId:'R',localPath:'/old.txt',nodeId:'N',status:'synced'});
  await h.engine.syncOnce();assert.equal(h.config().cursor,'2');assert.deepEqual([...h.states.keys()],['/new.txt']);
  assert.equal(h.requests.includes('/file/N/stream'),false);
});
test('F6 不误删被其他节点复用的旧映射',async t=>{
  const h=fixture(t,{changes:[move('MOVE')]});h.local('new.txt','bb');
  h.states.set('/old.txt',{rootId:'R',localPath:'/old.txt',nodeId:'X',status:'synced'});
  await h.engine.syncOnce();assert.equal(h.states.get('/old.txt').nodeId,'X');
});
test('F5 升级前加载排除路径并保留原件/映射',async t=>{
  const h=fixture(t,{upgrade:true,outside:true});h.local('excluded.txt','original-excluded');
  await h.manager.startSync('R','ROOT',h.root);
  assert.equal(h.requests[0],'/sync/roots/R/exclusions');assert.equal(h.manager.isSyncing('R'),true);
  assert.equal(fs.readFileSync(path.join(h.root,'excluded.txt'),'utf8'),'original-excluded');
  assert.equal(h.states.get('/excluded.txt').nodeId,'N');assert.equal(h.requests.includes('/file/N'),false);
});
for(const broken of ['exclusions','reconcile'])test('F5/F7 '+broken+' 失败清理后可重启',async t=>{
  const h=fixture(t,{upgrade:true,broken});
  await assert.rejects(()=>h.manager.startSync('R','ROOT',h.root));
  assert.equal(h.manager.isSyncing('R'),false);assert.equal(h.effects.watcherStarts,0);assert.equal(h.effects.timers,0);
  assert.equal(h.config().cursor,'1');assert.equal(h.config().syncVersion,3);
  if(broken==='exclusions')assert.equal(h.effects.reconciliations,0);
  h.recover();await h.manager.startSync('R','ROOT',h.root);
  assert.equal(h.manager.isSyncing('R'),true);assert.equal(h.effects.watcherStarts,1);assert.equal(h.effects.timers,1);
  assert.equal(h.effects.reconciliations,broken==='reconcile'?2:1);
});
