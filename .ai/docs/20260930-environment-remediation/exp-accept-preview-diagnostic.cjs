// 只读复现本验收自建静态预览的嵌套路由失败；不创建账号/团队/文件。
const fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('C:/Users/aoz/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const dist=path.resolve(__dirname,'../../../st-web/dist');
const result={at:new Date().toISOString(),scope:'本任务简单静态预览，非生产托管/S3',cases:[]};
let browser;
const server=http.createServer((req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1');const file=path.resolve(dist,'.'+decodeURIComponent(url.pathname));
 if(!file.startsWith(dist+path.sep)&&file!==dist){res.statusCode=400;res.end();return;}
 const target=fs.existsSync(file)&&fs.statSync(file).isFile()?file:path.join(dist,'index.html');
 const type={'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml'}[path.extname(target)]||'application/octet-stream';
 res.setHeader('Content-Type',type);res.setHeader('Cache-Control','no-store');fs.createReadStream(target).pipe(res);
});
(async()=>{
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(5174,'127.0.0.1',resolve);});
 browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});result.browser=browser.version();
 for(const pathname of ['/team/2105477988504240129','/team']){
  const page=await browser.newPage({serviceWorkers:'block'});const item={pathname,responses:[],errors:[]};
  const reads=[];
  page.on('pageerror',error=>item.errors.push(error.message));
  page.on('response',response=>{
   const url=new URL(response.url());
   if(url.port==='5174'&&/\/assets\//.test(url.pathname))reads.push((async()=>{
    const entry={path:url.pathname,status:response.status(),contentType:response.headers()['content-type']};
    if(entry.contentType?.startsWith('text/html'))entry.bodyPrefix=(await response.text()).slice(0,80);
    item.responses.push(entry);
   })());
  });
  await page.goto('http://127.0.0.1:5174'+pathname);await page.waitForTimeout(1200);await Promise.all(reads);
  item.bodyText=(await page.locator('body').innerText()).slice(0,120);item.finalPath=new URL(page.url()).pathname;
  result.cases.push(item);await page.close();
 }
})().catch(e=>{result.error=e.message;process.exitCode=1;}).finally(async()=>{
 if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
 fs.writeFileSync(path.join(__dirname,'exp-accept-preview-diagnostic.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result,null,2));
});
