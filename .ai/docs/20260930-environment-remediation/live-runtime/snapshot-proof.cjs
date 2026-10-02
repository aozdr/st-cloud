const fs=require('node:fs'); const path=require('node:path'); const crypto=require('node:crypto'); const assert=require('node:assert/strict');
const {createDesktopRuntime}=require('./desktop-runtime.cjs');
async function main(){
 const runtime=createDesktopRuntime(path.join(__dirname,'desktop-data'));
 const auth=JSON.parse(JSON.parse(fs.readFileSync(path.join(__dirname,'auth-storage.json'),'utf8'))['stcloud:auth']);
 const api=runtime.load('api-client.ts');api.setAuth(auth.token,auth.refreshToken,auth.sessionId);
 const db=runtime.load('database.ts');await db.initDatabase();
 const s=JSON.parse(fs.readFileSync(path.join(__dirname,'sync-scenario.json'),'utf8'));
 const before=db.getSyncConfig(s.rootId).cursor;
 const delta=await api.apiClient.get(`/sync/roots/${s.rootId}/delta`,{params:{since:before}});
 const engine=new (runtime.load('sync-engine.ts').SyncEngine)(s.root);await engine.syncOnce();
 assert.ok(db.getSyncConfig(s.rootId).cursor === delta.data.data.cursor,'当前真实游标须追上实际delta');
 const states=db.getAllSyncStates(s.rootId);assert.ok(states.every(v=>v.localPath===v.localPath.replace(/\/+/g,'/')),'双斜杠旧映射已恢复');
 assert.ok(db.getSyncState(s.rootId,'/destination/renamed.txt')?.nodeId===s.nodeId,'最终规范映射同节点');
 const recoveryRoot=path.join(__dirname,'desktop-data/sync-recovery',s.rootId);
 const recoveries=fs.readdirSync(recoveryRoot).map(operationId=>{
  const dir=path.join(recoveryRoot,operationId);const manifest=JSON.parse(fs.readFileSync(path.join(dir,'manifest.json'),'utf8'));
  return {operationId,originalPath:manifest.originalPath,status:manifest.status,files:manifest.entries.map(relative=>({relative,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(dir,'files',relative))).digest('hex')}))};
 });
 assert.ok(recoveries.some(v=>v.operationId==='legacy-'+s.nodeId),'旧保全审计副本保留');
 assert.ok(recoveries.every(v=>v.status==='complete'),'各轮保全清单完整');
 const result={executedAt:new Date().toISOString(),cursorBefore:before,cursorAfter:db.getSyncConfig(s.rootId).cursor,states,recoveries,canonicalMappings:true,originalRecoveryPreserved:true};
 fs.writeFileSync(path.join(__dirname,'sync-final-snapshot.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify({check:'final_mapping_cursor_recovery_proof',cursorBefore:before,cursorAfter:result.cursorAfter,recoveryCount:recoveries.length,canonicalMappings:true,originalRecoveryPreserved:true}));
}
main().catch(e=>{console.log(JSON.stringify({failed:e.message}));process.exitCode=1;});
