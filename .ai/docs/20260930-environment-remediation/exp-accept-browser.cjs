// 当前真实 Web/Chrome/8080 的独立体验验收，仅创建和操作专用 fixture。
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { chromium } = require('C:/Users/aoz/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../../..');
const dist = path.join(root, 'st-web/dist');
const report = { startedAt:new Date().toISOString(), backend:'http://127.0.0.1:8080', checks:[], requests:[], pageErrors:[], consoleErrors:[] };
const account = 'env_exp_' + Date.now().toString(36) + crypto.randomBytes(3).toString('hex');
const password = 'Fixture-' + crypto.randomBytes(16).toString('hex');
const spaceName = 'EXP团队-' + account;
const folderName = '验收文件夹-' + account;
report.fixture = { username:account, spaceName, folderName };
let browser, server, page;
function record(name, details={}) { report.checks.push({name,pass:true,...details}); console.log(JSON.stringify({check:name,...details})); }
async function shot(name) { await page.screenshot({path:path.join(__dirname,'exp-accept-'+name+'.png'),fullPage:true}); }
async function main() {
 const ready = await fetch(report.backend+'/api/auth/ping');
 if (!ready.ok) throw new Error('8080 not ready: '+ready.status);
 server = http.createServer((req,res)=>{
  const url = new URL(req.url,'http://127.0.0.1');
  const file = path.resolve(dist, '.'+decodeURIComponent(url.pathname));
  if (!file.startsWith(dist+path.sep) && file!==dist) { res.statusCode=400; res.end(); return; }
  const target = fs.existsSync(file)&&fs.statSync(file).isFile()?file:path.join(dist,'index.html');
  const types={'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.woff2':'font/woff2'};
  res.setHeader('Content-Type',types[path.extname(target)]||'application/octet-stream');
  res.setHeader('Cache-Control','no-store');
  fs.createReadStream(target).pipe(res);
 });
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(5174,'127.0.0.1',resolve);});
 browser = await chromium.launch({headless:true,executablePath:process.env.STCLOUD_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 report.browserVersion=browser.version();
 const context = await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'});
 page=await context.newPage(); page.setDefaultTimeout(15000);
 page.on('pageerror',e=>report.pageErrors.push(e.message));
 page.on('console',msg=>{if(msg.type()==='error'&&!msg.text().includes('token')) report.consoleErrors.push(msg.text());});
 page.on('response',async response=>{
  const url = new URL(response.url());
  if(url.port==='8080'&&url.pathname.startsWith('/api/')) {
   const item={method:response.request().method(),path:url.pathname,http:response.status()};
   if(['POST','DELETE','PUT'].includes(item.method)) {
    try{ const data=await response.json(); item.businessCode=data.code; item.message=data.message;
      if(url.pathname==='/api/team/space'&&data.code===200) report.fixture.spaceId=data.data?.id;
      if(url.pathname.endsWith('/folder')&&data.code===200) report.fixture.folderId=data.data?.id;
    }catch{}
   }
   report.requests.push(item);
  }
 });
 await page.goto('http://127.0.0.1:5174/login');
 await page.getByRole('button',{name:'立即注册',exact:true}).click();
 await page.getByLabel('用户名',{exact:true}).fill(account);
 await page.getByLabel('密码',{exact:true}).fill(password);
 const registration=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/register'&&r.request().method()==='POST');
 await page.getByRole('button',{name:'注册',exact:true}).click();
 const reg=await registration; const regBody=await reg.json();
 if(reg.status()!==200||regBody.code!==200)throw new Error('registration rejected: HTTP '+reg.status()+', code '+regBody.code);
 report.fixture.userId=regBody.data.userId||regBody.data.user?.userId;
 record('真实注册页面专用账号成功',{http:reg.status(),businessCode:regBody.code});
 await page.waitForURL('http://127.0.0.1:5174/');
 await shot('registered');
 const nav=await page.locator('aside nav a').evaluateAll(links=>links.map(x=>({name:x.textContent.trim(),href:x.getAttribute('href')})));
 if(new Set(nav.map(x=>x.href)).size!==nav.length)throw new Error('duplicate sidebar navigation');
 if(nav.some(x=>x.href==='/admin'||x.href==='/sync'||x.href==='/transfers'))throw new Error('ordinary Web fixture sees unexpected admin/Electron entries');
 if(!nav.some(x=>x.href==='/following'))throw new Error('following entry missing after merge');
 record('普通Web账号Sidebar入口完整且无重复或越权入口',{navigation:nav});
 await page.getByRole('button',{name:'折叠侧边栏',exact:true}).click();
 await page.goto('http://127.0.0.1:5174/recycle');
 await page.getByRole('button',{name:'展开侧边栏',exact:true}).waitFor();
 if(await page.evaluate(()=>localStorage.getItem('sidebarCollapsed'))!=='1')throw new Error('collapse preference not saved');
 record('Sidebar折叠偏好跨页面保持');await shot('sidebar-collapsed');
 await page.getByRole('button',{name:'展开侧边栏',exact:true}).click();
 await page.getByRole('button',{name:'主题设置',exact:true}).click();
 await page.getByRole('button',{name:'深色',exact:true}).click();
 await page.getByRole('button',{name:'关闭设置',exact:true}).click();
 await page.goto('http://127.0.0.1:5174/team');
 if(!await page.evaluate(()=>document.documentElement.classList.contains('dark')))throw new Error('dark theme lost on navigation');
 record('Sidebar主题设置跨页面保持深色');await shot('sidebar-dark');
 await page.getByRole('button',{name:'主题设置',exact:true}).click();
 await page.getByRole('button',{name:'浅色',exact:true}).click();
 await page.getByRole('button',{name:'关闭设置',exact:true}).click();
 await page.goto('http://127.0.0.1:5174/team');
 await page.getByRole('button',{name:'创建空间',exact:true}).click();
 await page.getByPlaceholder('如：产品研发组').fill(spaceName);
 await page.getByPlaceholder('简要描述空间用途').fill('20261001 独立体验验收专用，保留审计。');
 const creating=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/team/space'&&r.request().method()==='POST');
 await page.getByRole('button',{name:'创建空间',exact:true}).last().click();
 const created=await creating; const createdBody=await created.json();
 if(created.status()!==200||createdBody.code!==200) {await shot('space-rejected');throw new Error('team creation rejected: HTTP '+created.status()+', code '+createdBody.code);}
 record('真实页面创建团队空间',{http:created.status(),businessCode:createdBody.code});
 await page.getByRole('heading',{name:spaceName,exact:true}).click();
 await page.waitForURL(/\/team\/\d+$/);
 await page.getByRole('button',{name:'新建',exact:true}).click();
 await page.getByRole('dialog').getByRole('button',{name:'新建文件夹',exact:true}).click();
 await page.getByPlaceholder('文件夹名称').fill(folderName);
 const folding=page.waitForResponse(r=>/\/api\/team\/\d+\/folder$/.test(new URL(r.url()).pathname)&&r.request().method()==='POST');
 await page.getByRole('button',{name:'创建',exact:true}).click();
 const folded=await folding;const foldedBody=await folded.json();
 if(folded.status()!==200||foldedBody.code!==200)throw new Error('folder creation rejected: HTTP '+folded.status()+', code '+foldedBody.code);
 await page.getByText(folderName,{exact:true}).first().waitFor();
 record('真实页面新建团队文件夹',{http:folded.status(),businessCode:foldedBody.code});
 await shot('team-before-delete');
 await page.getByText(folderName,{exact:true}).first().click();
 await page.getByRole('button',{name:'删除',exact:true}).first().click();
 const dialog=page.getByRole('dialog',{name:'删除文件',exact:true});
 await dialog.waitFor(); await shot('delete-confirmation');
 const deleting=page.waitForResponse(r=>/\/api\/team\/\d+\/files\/delete$/.test(new URL(r.url()).pathname)&&r.request().method()==='POST');
 await dialog.getByRole('button',{name:'删除',exact:true}).click();
 const deleted=await deleting;const deletedBody=await deleted.json();
 if(deleted.status()!==200||deletedBody.code!==200)throw new Error('folder deletion rejected: HTTP '+deleted.status()+', code '+deletedBody.code);
 await page.getByText(folderName,{exact:true}).waitFor({state:'hidden'});
 record('真实页面团队软删除并移出列表',{http:deleted.status(),businessCode:deletedBody.code});
 await shot('team-after-delete');
 await page.getByRole('link',{name:'回收站',exact:true}).click();
 await page.waitForURL(/\/recycle$/);
 const row=page.getByRole('row').filter({hasText:folderName});await row.waitFor();
 const rowText=await row.innerText();
 record('真实回收站显示团队专用文件夹',{display:rowText});
 await shot('recycle');
 const restoring=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/recycle/restore'&&r.request().method()==='POST');
 await row.getByRole('button',{name:'恢复',exact:true}).click();
 const restored=await restoring;const restoredBody=await restored.json();
 if(restored.status()!==200||restoredBody.code!==200)throw new Error('restore rejected: HTTP '+restored.status()+', code '+restoredBody.code);
 await row.waitFor({state:'hidden'});
 record('真实回收站恢复成功并移出回收列表',{http:restored.status(),businessCode:restoredBody.code});
 await page.getByRole('link',{name:'团队空间',exact:true}).click();
 await page.getByRole('heading',{name:spaceName,exact:true}).click();
 await page.getByText(folderName,{exact:true}).first().waitFor();
 record('恢复后返回原团队列表可见同名节点');await shot('team-restored');
 await page.getByRole('link',{name:'团队空间',exact:true}).click();
 await page.reload();await page.getByRole('heading',{name:spaceName,exact:true}).waitFor();
 const storage=await page.evaluate(()=>{const x=JSON.parse(localStorage.getItem('stcloud:auth')||'null');return {pairPresent:!!(x?.token&&x?.refreshToken),revision:x?.revision,sessionIdPresent:!!x?.sessionId};});
 if(!storage.pairPresent)throw new Error('browser atomic auth pair missing');
 record('Chrome重新加载保持登录与成对令牌',{...storage});
 for(let round=1;round<=2;round++) {
  const before=await page.evaluate(()=>JSON.parse(localStorage.getItem('stcloud:auth')));
  const refreshing=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/refresh'&&r.request().method()==='POST');
  const beforeRefreshCount=report.requests.filter(x=>x.path==='/api/auth/refresh').length;
  await page.evaluate(()=>{
   const saved=JSON.parse(localStorage.getItem('stcloud:auth'));
   // 仅让本测试浏览器的 access 被启动逻辑视为过期；refresh 仍来自真实服务。
   const payload=btoa(JSON.stringify({exp:1,iat:1}));
   saved.token='eyJhbGciOiJIUzI1NiJ9.'+payload+'.test-expired-access';
   localStorage.setItem('stcloud:auth',JSON.stringify(saved));sessionStorage.setItem('accessToken',saved.token);
  });
  await page.reload();
  const response=await refreshing;const body=await response.json();
  await page.getByRole('heading',{name:spaceName,exact:true}).waitFor();
  const after=await page.evaluate(()=>JSON.parse(localStorage.getItem('stcloud:auth')));
  if(response.status()!==200||body.code!==200||after.refreshToken===before.refreshToken||after.token===before.token)throw new Error('browser real refresh pair not rotated, round '+round);
  if(report.requests.filter(x=>x.path==='/api/auth/refresh').length-beforeRefreshCount!==1)throw new Error('duplicate startup refresh, round '+round);
  const oldRejected=await page.evaluate(async old=>{
   const response=await fetch('http://127.0.0.1:8080/api/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:old})});
   const body=await response.json();return {http:response.status(),businessCode:body.code};
  },before.refreshToken);
  if(![1004,1005,401,403].includes(oldRejected.businessCode)&&![401,403].includes(oldRejected.http))throw new Error('old refresh not rejected');
  record('Chrome真实后端连续轮换第'+round+'轮及旧refresh拒绝',{refreshCalls:1,pairChanged:true,oldRejected,trigger:'专用浏览器过期access fixture后重新加载，执行当前启动恢复逻辑'});
 }
 await page.getByRole('heading',{name:spaceName,exact:true}).click();
 await page.getByText(folderName,{exact:true}).first().waitFor();
 await context.setOffline(true);
 await page.getByRole('button',{name:'刷新',exact:true}).click();
 await page.waitForTimeout(800);
 const offlineStorage=await page.evaluate(()=>{const x=JSON.parse(localStorage.getItem('stcloud:auth')||'null');return !!(x?.token&&x?.refreshToken);});
 if(!offlineStorage||!/\/team\/\d+$/.test(page.url()))throw new Error('temporary offline unexpectedly cleared session or navigated to login');
 record('真实页面临时离线刷新不退出且保留成对令牌',{pairPresent:offlineStorage});await shot('temporary-offline');
 await context.setOffline(false);await page.getByRole('button',{name:'刷新',exact:true}).click();
 await page.getByText(folderName,{exact:true}).first().waitFor();record('恢复连接后原登录会话继续访问团队页面');
 report.status='passed';
}
main().catch(async e=>{report.status='failed';report.failure=e.message;console.error('FAIL '+e.message);if(page)await shot('failure').catch(()=>{});process.exitCode=1;}).finally(async()=>{
 report.finishedAt=new Date().toISOString();
 if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));
 fs.writeFileSync(path.join(__dirname,'exp-accept-browser-results.json'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify({status:report.status,checks:report.checks.length,pageErrors:report.pageErrors}));
});
