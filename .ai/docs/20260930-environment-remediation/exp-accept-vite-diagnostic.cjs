// 使用 Vite 自带 preview 验证当前 dist 的 Web 深链接资源；无构建或业务写入。
const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const {chromium}=require('C:/Users/aoz/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const web=path.resolve(__dirname,'../../../st-web');
const result={at:new Date().toISOString(),scope:'当前dist，Vite原生preview，匿名只读',cases:[]};
let browser,preview;
(async()=>{
 const html=fs.readFileSync(path.join(web,'dist/index.html'),'utf8');
 const scriptUrl=html.match(/<script[^>]+src="([^"]+)"/)?.[1];
 const base=scriptUrl?.startsWith('/assets/')?'/':scriptUrl?.startsWith('./assets/')?'./':null;
 if(!base)throw new Error('unrecognized current dist module base');
 result.currentDistBase=base;result.currentDistScript=scriptUrl;
 result.configFile='false，避免编译配置文件产生产品临时文件；base从实际dist入口读取，root/outDir与当前工程一致';
 const vite=await import(pathToFileURL(path.join(web,'node_modules/vite/dist/node/index.js')).href);
 preview=await vite.preview({configFile:false,root:web,base,build:{outDir:'dist'},preview:{host:'127.0.0.1',port:5174,strictPort:true}});
 browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});result.browser=browser.version();
 for(const pathname of ['/team/2105477988504240129','/team']){
  const page=await browser.newPage({serviceWorkers:'block'});const item={pathname,responses:[],errors:[]};const reads=[];
  page.on('pageerror',e=>item.errors.push(e.message));
  page.on('response',response=>{
   const url=new URL(response.url());
   if(url.port==='5174'&&/\/assets\//.test(url.pathname))reads.push((async()=>{
    const entry={path:url.pathname,status:response.status(),contentType:response.headers()['content-type']};
    if(entry.contentType?.startsWith('text/html'))entry.bodyPrefix=(await response.text()).slice(0,80);
    item.responses.push(entry);
   })());
  });
  await page.goto('http://127.0.0.1:5174'+pathname);await page.waitForTimeout(1200);await Promise.all(reads);
  item.bodyText=(await page.locator('body').innerText()).slice(0,100);item.finalPath=new URL(page.url()).pathname;
  result.cases.push(item);await page.close();
  if(!item.bodyText||item.finalPath!=='/login'||item.responses.some(x=>x.status!==200||x.contentType?.startsWith('text/html'))||item.errors.length)throw new Error('anonymous deep-link asset/render validation failed for '+pathname);
 }
 result.status='passed';
})().catch(e=>{result.error=e.message;process.exitCode=1;}).finally(async()=>{
 if(browser)await browser.close();if(preview)await new Promise(resolve=>preview.httpServer.close(resolve));
 fs.writeFileSync(path.join(__dirname,'exp-accept-vite-diagnostic.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result,null,2));
});
