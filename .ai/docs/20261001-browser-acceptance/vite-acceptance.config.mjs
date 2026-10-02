import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execAsync=promisify(execFile);
const fixture={userId:'2105477981680107521',username:'env_exp_muow0kkf12c526',spaceId:'2105477988504240129',folderId:'2105477991222149121'};
const origin='http://127.0.0.1:5176', team='/team/'+fixture.spaceId;
const nonce=crypto.randomBytes(24).toString('hex');
const output=path.resolve('.ai/docs/20261001-browser-acceptance/network-evidence.json');
const report={startedAt:new Date().toISOString(),fixture,backend:'http://127.0.0.1:8080',origin,mode:'native-vite-preview-with-test-only-middleware',requests:[],checks:[],observations:[],offlineFailures:0};
let offline=false, previousRefresh=null;
const sha=v=>v?crypto.createHash('sha256').update(v).digest('hex'):null;
const save=()=>fs.writeFileSync(output,JSON.stringify(report,null,2));
function send(res,status,value){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
async function readBody(req){let s='';for await(const chunk of req){s+=chunk;if(s.length>12000)throw Error('body too large');}return s?JSON.parse(s):{};}
async function refresh(value){const r=await fetch('http://127.0.0.1:8080/api/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:value})});return{http:r.status,result:await r.json()};}
const html=String.raw`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>浏览器补验控制</title><style>body{font:16px system-ui;max-width:850px;margin:32px auto;line-height:1.6}button{display:block;margin:12px 0;padding:10px 16px}pre{white-space:pre-wrap}</style><h1>专用账号浏览器补验</h1><p>复用已恢复目录，不创建账号/空间。过期凭据及断网仅为本入口受控注入。</p><button id="recover">恢复测试会话并进入原团队</button><button id="round1">注入第1轮过期凭据并进入团队</button><button id="round2">注入第2轮过期凭据并进入团队</button><button id="offline">断开本入口 API 并进入团队</button><button id="online">恢复本入口 API 并进入团队</button><button id="old">验证上一轮旧 refresh 被拒绝</button><button id="observe">记录脱敏持久状态</button><button id="clear">结束补验并清除本入口会话</button><pre id="status">准备就绪</pre><script>
const nonce=${JSON.stringify(nonce)}, expectedOrigin=${JSON.stringify(origin)}, team=${JSON.stringify(team)}, key='stcloud:auth';
const status=document.getElementById('status');
async function command(action,data={}){const r=await fetch('/__acceptance/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-Acceptance-Nonce':nonce},body:JSON.stringify(data)});const v=await r.json();if(!r.ok)throw Error(v.error||'操作失败');return v;}
const auth=()=>JSON.parse(localStorage.getItem(key)||'null');
function persist(a){localStorage.setItem(key,JSON.stringify(a));localStorage.setItem('refreshToken',a.refreshToken);if(a.token)sessionStorage.setItem('accessToken',a.token);else sessionStorage.removeItem('accessToken');}
async function digest(v){if(!v)return null;return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(v)))).map(x=>x.toString(16).padStart(2,'0')).join('');}
async function observe(label){const a=auth(),data={label,accessPresent:!!a?.token,refreshPresent:!!a?.refreshToken,accessSha:await digest(a?.token),refreshSha:await digest(a?.refreshToken),revision:a?.revision??null,serverMatches:a?.serverUrl===expectedOrigin};await command('observe',data);status.textContent=JSON.stringify(data,null,2);}
async function enter(label){await observe(label);location.assign(team);}
const run=fn=>async()=>{try{await fn();}catch(e){status.textContent='失败：'+e.message;}};
document.getElementById('recover').onclick=run(async()=>{const d=await command('recover');localStorage.setItem('stcloud:serverUrl',expectedOrigin);persist({token:d.token,refreshToken:d.refreshToken,sessionId:crypto.randomUUID(),serverUrl:expectedOrigin,revision:1});await enter('restored-fixture-session');});
for(const round of [1,2])document.getElementById('round'+round).onclick=run(async()=>{const a=auth();if(!a?.token||!a?.refreshToken)throw Error('缺少当前会话');await observe('before-round-'+round);const p=a.token.split('.');if(p.length!==3)throw Error('access格式错误');p[2]=(p[2][0]==='A'?'B':'A')+p[2].slice(1);a.token=p.join('.');a.revision++;persist(a);await enter('invalid-access-round-'+round);});
document.getElementById('offline').onclick=run(async()=>{await observe('before-offline');await command('network',{offline:true});const a=auth();a.token=null;a.revision++;persist(a);await enter('offline-refresh-only');});
document.getElementById('online').onclick=run(async()=>{await observe('after-offline-before-reconnect');await command('network',{offline:false});await enter('online-recovery');});
document.getElementById('old').onclick=run(async()=>{status.textContent=JSON.stringify(await command('old-check'),null,2);});
document.getElementById('observe').onclick=run(async()=>observe('manual-observation'));
document.getElementById('clear').onclick=run(async()=>{await observe('before-cleanup');for(const k of [key,'refreshToken','stcloud:serverUrl'])localStorage.removeItem(k);sessionStorage.removeItem('accessToken');await command('complete');status.textContent='补验结束，本入口测试会话已清除；账号、空间和目录保留。';});
</script></html>`;
export default{root:path.resolve('st-web'),base:'/',preview:{host:'127.0.0.1',port:5176,strictPort:true},plugins:[{name:'fixture-only-browser-acceptance',configurePreviewServer(server){server.middlewares.use(async(req,res,next)=>{
 const pathname=new URL(req.url,origin).pathname;
 // 本轮只验认证/回收末段；测试控制页不参与PWA导航缓存，保持业务构建字节不变。
 if(pathname==='/sw.js'){res.writeHead(200,{'Content-Type':'application/javascript','Cache-Control':'no-store'});res.end('self.addEventListener("install",()=>self.skipWaiting());');return;}
 if(pathname==='/__acceptance'||pathname==='/__acceptance/'){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'"});res.end(html);return;}
 if(pathname.startsWith('/__acceptance/')){
  // 用户明确授权精确测试键；同源nonce阻止其他页面触发凭据读取。
  if(req.method!=='POST'||req.headers.origin!==origin||req.headers['x-acceptance-nonce']!==nonce){send(res,403,{error:'fixture-only origin required'});return;}
  try{const d=await readBody(req),action=pathname.slice(14);
   if(action==='recover'){
    const {stdout}=await execAsync('C:/Users/aoz/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe',['exec','stcloud-redis','redis-cli','--raw','GET','stcloud:refresh:'+fixture.userId],{windowsHide:true});
    const current=stdout.trim();if(!current||current.split('.').length!==3)throw Error('fixture refresh missing');
    const {http:status,result}=await refresh(current);
    if(status!==200||result.code!==200||String(result.data?.userId)!==fixture.userId||result.data?.username!==fixture.username)throw Error('fixture identity rejected');
    previousRefresh=current;report.checks.push({name:'authorized-fixture-recovery',http:status,code:result.code,userId:fixture.userId,oldRefreshSha:sha(current),newRefreshSha:sha(result.data.refreshToken)});save();send(res,200,{token:result.data.token,refreshToken:result.data.refreshToken});return;
   }
   if(action==='network'){offline=d.offline===true;report.checks.push({name:'network-injection',offline,at:new Date().toISOString()});save();send(res,200,{offline});return;}
   if(action==='observe'){const keys=['label','accessPresent','refreshPresent','accessSha','refreshSha','revision','serverMatches'];report.observations.push({...Object.fromEntries(keys.filter(k=>k in d).map(k=>[k,d[k]])),at:new Date().toISOString()});save();send(res,200,{recorded:true});return;}
   if(action==='old-check'){if(!previousRefresh)throw Error('no old refresh');const {http:status,result}=await refresh(previousRefresh),pass=status===200&&result.code===1005;report.checks.push({name:'old-refresh-rejected',http:status,code:result.code,pass});save();send(res,200,{http:status,code:result.code,pass});return;}
   if(action==='complete'){offline=false;previousRefresh=null;report.finishedAt=new Date().toISOString();report.status='browser-actions-completed';save();send(res,200,{completed:true});return;}
   send(res,404,{error:'unknown test action'});return;
  }catch{report.checks.push({name:'helper-error',path:pathname,at:new Date().toISOString()});save();send(res,500,{error:'test operation failed; no credential logged'});return;}
 }
 if(pathname.startsWith('/api/')){
  if(offline){report.offlineFailures++;report.requests.push({path:pathname,method:req.method,result:'injected-connection-reset'});save();req.socket.destroy();return;}
  let input='';req.on('data',chunk=>{if(pathname==='/api/auth/refresh')input+=chunk;});
  // 只代理本临时入口，后端/Docker不停止；持久证据仅含摘要和业务状态。
  const upstream=http.request({hostname:'127.0.0.1',port:8080,path:req.url,method:req.method,headers:{...req.headers,host:'127.0.0.1:8080','accept-encoding':'identity'}},response=>{
   const chunks=[];if(pathname==='/api/auth/refresh')response.on('data',chunk=>chunks.push(chunk));
   response.on('end',()=>{const item={path:pathname,method:req.method,http:response.statusCode,at:new Date().toISOString()};if(pathname==='/api/auth/refresh'){try{const v=JSON.parse(Buffer.concat(chunks).toString());item.code=v.code;if(v.code===200){const old=JSON.parse(input).refreshToken;previousRefresh=old;item.oldRefreshSha=sha(old);item.newRefreshSha=sha(v.data.refreshToken);item.newAccessSha=sha(v.data.token);item.userId=String(v.data.userId);}}catch{item.parseError=true;}}report.requests.push(item);save();});
   res.writeHead(response.statusCode,response.headers);response.pipe(res);
  });upstream.on('error',()=>{report.requests.push({path:pathname,result:'upstream-error'});save();if(!res.headersSent)send(res,502,{error:'backend unavailable'});else res.destroy();});req.pipe(upstream);return;
 }
 next();
});}}]};
