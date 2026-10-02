/* Current-source browser regression. Run with the existing Node runtime; no install. */
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { createRequire } = require('node:module');
const crypto = require('node:crypto');
const taskRoot = __dirname;
const repoRoot = path.resolve(taskRoot, '../../..');
const webRoot = path.join(repoRoot, 'st-web');
const webRequire = createRequire(path.join(webRoot, 'package.json'));
const esbuild = webRequire('esbuild');
const postcss = webRequire('postcss');
const tailwind = webRequire('tailwindcss');
const loadConfig = webRequire('tailwindcss/loadConfig');
const { chromium } = require('C:/Users/aoz/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const label = process.argv.find(a => a.startsWith('--label='))?.split('=')[1] || 'final';
if (!/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid artifact label');
const sourcePath = path.join(webRoot, 'src/components/preview/PreviewModal.tsx');
const source = fs.readFileSync(sourcePath, 'utf8');
const results = [];
const errors = [];
const requests = [];
const responses = new Map();
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="#293347"/><circle cx="400" cy="300" r="100" fill="#9ca9c6"/></svg>`;
const entry = `
import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {flushSync} from 'react-dom';
import {MemoryRouter} from 'react-router-dom';
import PreviewModal from './src/components/preview/PreviewModal';
const original = [
 {id:'101',name:'A 照片.jpg',suffix:'jpg',nodeType:1,updatedAt:'v0'},
 {id:'102',name:'B 照片.jpg',suffix:'jpg',nodeType:1,updatedAt:'v0'},
 {id:'103',name:'C 图形.webp',suffix:'webp',nodeType:1,updatedAt:'v0'},
];
window.__test = {mounts:0, closes:0, downloads:0};
function Fixture(){
 const [files,setFiles] = useState(original);
 const [share,setShare] = useState({shareCode:'alpha',password:'密 码&=1'});
 React.useEffect(()=>{window.__test.mounts++;},[]);
 window.__test.update=(id,updatedAt)=>flushSync(()=>setFiles(f=>f.map(x=>x.id===id?{...x,updatedAt}:x)));
 window.__test.share=(shareCode,password)=>flushSync(()=>setShare({shareCode,password}));
 window.__test.captureError=(id,key)=>{
  const button=document.querySelector('button[aria-label="预览 '+original.find(f=>f.id===id).name+'"]');
  const image=button.querySelector('img');
  if(!image) throw new Error('Missing source image');
  const props=image[Object.keys(image).find(k=>k.startsWith('__reactProps$'))];
  window.__test[key]=props.onError;
  return image.src;
 };
 window.__test.invoke=(key,id)=>{
  flushSync(()=>window.__test[key]());
  return !!document.querySelector('button[aria-label="预览 '+original.find(f=>f.id===id).name+'"] img');
 };
 return <PreviewModal files={files} currentIndex={0} shareContext={share}
  onDownload={()=>window.__test.downloads++} onClose={()=>window.__test.closes++}/>;
}
createRoot(document.getElementById('root')).render(<MemoryRouter><Fixture/></MemoryRouter>);
`;

function luminance(rgb) {
 const c=rgb.map(x=>{x/=255;return x<=.04045?x/12.92:((x+.055)/1.055)**2.4});
 return c[0]*.2126+c[1]*.7152+c[2]*.0722;
}
function ratio(a,b) { const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05); }
async function main(){
 const bundle = await esbuild.build({stdin:{contents:entry,resolveDir:webRoot,sourcefile:'web-browser-fixture.tsx',loader:'tsx'},
  bundle:true,write:false,format:'iife',platform:'browser',jsx:'automatic',outfile:'fixture.js',
  define:{'process.env.NODE_ENV':'"development"','import.meta.env':'{}'},logLevel:'warning'});
 const js=bundle.outputFiles.find(f=>f.path.endsWith('.js')).text;
 const cssConfig=loadConfig(path.join(webRoot,'tailwind.config.js'));
 const css=(await postcss([tailwind({...cssConfig,content:[{raw:source,extension:'tsx'}]})])
  .process(fs.readFileSync(path.join(webRoot,'src/index.css'),'utf8'),{from:path.join(webRoot,'src/index.css')})).css;
 const server=http.createServer((req,res)=>{
  const u=new URL(req.url,'http://localhost');
  if(u.pathname==='/fixture.js'){res.setHeader('Content-Type','application/javascript');res.end(js);return;}
  if(u.pathname==='/fixture.css'){res.setHeader('Content-Type','text/css');res.end(css);return;}
  if(u.pathname.startsWith('/api/share/access/thumbnail/')){
   requests.push({pathname:u.pathname,nodeId:u.searchParams.get('nodeId'),version:u.searchParams.get('v'),password:u.searchParams.get('password')});
   const status=responses.get(u.searchParams.get('nodeId')+':'+u.searchParams.get('v'))||200;
   res.statusCode=status;res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type',status===200?'image/svg+xml':'text/plain');
   res.end(status===200?svg:'Controlled thumbnail failure');return;
  }
  if(u.pathname.startsWith('/api/share/access/stream/')){res.setHeader('Content-Type','image/svg+xml');res.end(svg);return;}
  if(u.pathname==='/'){res.setHeader('Content-Type','text/html');res.end('<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/fixture.css"></head><body><div id="root"></div><script src="/fixture.js"></script></body></html>');return;}
  res.statusCode=404;res.end();
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,executablePath:process.env.STCLOUD_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page=await browser.newPage({viewport:{width:1360,height:900},deviceScaleFactor:1});
  page.setDefaultTimeout(5000);
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.getByRole('button',{name:'预览 A 照片.jpg',exact:true}).waitFor();
  const button=(name)=>page.getByRole('button',{name:'预览 '+name,exact:true});
  const names=['A 照片.jpg','B 照片.jpg','C 图形.webp'];
  const active=async name=>button(name).evaluate(el=>el.classList.contains('border-primary-400'));
  async function check(name,fn){try{const details=await fn();results.push({name,pass:true,details});console.log('PASS '+name);}catch(e){results.push({name,pass:false,error:e.message});console.log('FAIL '+name+': '+e.message);}}
  const assert=(condition,message)=>{if(!condition)throw new Error(message);};
  async function waitImage(name){await button(name).locator('img').waitFor();await page.waitForFunction(n=>{const i=document.querySelector('button[aria-label="预览 '+n+'"] img');return i&&i.complete&&i.naturalWidth>0;},name);}
  async function tabTo(name){for(let i=0;i<35;i++){await page.keyboard.press('Tab');const a=await page.evaluate(()=>document.activeElement?.getAttribute('aria-label'));if(a===name)return;}throw new Error('Tab cannot reach '+name);}
  await waitImage(names[0]);await waitImage(names[1]);
  await page.waitForTimeout(250); // Allow the real fade-in/selection transitions to finish before metrics.
  await check('按钮可访问名称与无装饰图片名冲突',async()=>{
   for(const name of names)assert(await button(name).count()===1,'Missing unique '+name);
   assert(await button(names[2]).locator('svg[aria-hidden="true"]').count()===1,'Unsupported icon not aria-hidden');
   return names.map(n=>'预览 '+n);
  });
  await check('Tab 与 Space 选择并保留焦点',async()=>{
   await tabTo('预览 '+names[1]);await page.keyboard.press('Space');
   assert(await active(names[1]),'Space did not select B');
   assert(await button(names[1]).evaluate(e=>e===document.activeElement),'Space lost focus');return 'B selected';
  });
  await check('左右键切换且焦点保持',async()=>{
   await page.keyboard.press('ArrowRight');assert(await active(names[2]),'ArrowRight did not select C');
   await page.keyboard.press('ArrowLeft');assert(await active(names[1]),'ArrowLeft did not select B');
   assert(await button(names[1]).evaluate(e=>e===document.activeElement),'Arrow key lost focus');return 'B → C → B';
  });
  await check('Tab 与 Enter 选择',async()=>{await tabTo('预览 '+names[2]);await page.keyboard.press('Enter');assert(await active(names[2]),'Enter did not select C');return 'C selected';});
  await check('左右键到边界不越界',async()=>{
   await page.keyboard.press('ArrowRight');assert(await active(names[2]),'Right boundary changed');
   await page.keyboard.press('ArrowLeft');await page.keyboard.press('ArrowLeft');await page.keyboard.press('ArrowLeft');
   assert(await active(names[0]),'Left boundary changed');return 'A/C boundaries retained';
  });
  await page.evaluate(()=>window.__test.captureError('102','oldV0Error'));
  const parentHandle=await button(names[1]).elementHandle();
  await check('同一挂载 updatedAt 更新请求 URL',async()=>{
   await page.evaluate(()=>window.__test.update('102','v2'));await waitImage(names[1]);
   assert(await button(names[1]).evaluate((el,old)=>el===old,parentHandle),'Button remounted');
   assert(await page.evaluate(()=>window.__test.mounts)===1,'Fixture remounted');
   assert(requests.some(r=>r.nodeId==='102'&&r.version==='v2'),'Version request missing');
   return requests.filter(r=>r.nodeId==='102');
  });
  await page.evaluate(()=>window.__test.captureError('102','oldV2Error'));
  responses.set('102:v3',503);
  await page.evaluate(()=>window.__test.update('102','v3'));
  await button(names[1]).locator('svg').waitFor();
  await check('新 URL 失败只显示降级且保留名称',async()=>{
   assert(await button(names[1]).locator('img').count()===0,'Failed src still shown');
   assert(await button(names[1]).locator('svg[aria-hidden="true"]').count()===1,'Missing failure icon');
   return 'HTTP 503 fallback';
  });
  await check('旧异步 error 不覆盖新 URL 的失败状态',async()=>{
   const imageAfterOld=await page.evaluate(()=>window.__test.invoke('oldV2Error','102'));
   assert(!imageAfterOld,'Old v2 error cleared the current v3 fallback');return 'Old callback ignored synchronously';
  });
  await button(names[1]).locator('svg').waitFor();
  await check('同一挂载失败后新 updatedAt 恢复',async()=>{
   await page.evaluate(()=>window.__test.update('102','v4'));await waitImage(names[1]);
   assert(await page.evaluate(()=>window.__test.mounts)===1,'Fixture remounted');return 'v3 failure → v4 image';
  });
  await check('旧 error 不影响已经成功的新图片',async()=>{
   const hasImage=await page.evaluate(()=>window.__test.invoke('oldV0Error','102'));
   assert(hasImage,'Old error removed new image');await waitImage(names[1]);return 'v4 image retained';
  });
  await check('URL 变更返回先前成功版本仍重新加载',async()=>{
   await page.evaluate(()=>window.__test.update('102','v0'));await waitImage(names[1]);return 'v4 → v0 image';
  });
  await check('同一挂载分享来源与密码变化重新请求',async()=>{
   await page.evaluate(()=>window.__test.share('beta','新&密码 =2'));await waitImage(names[0]);await waitImage(names[1]);
   assert(requests.some(r=>r.pathname.endsWith('/beta')&&r.nodeId==='102'&&r.password==='新&密码 =2'),'New share/password not preserved');
   assert(await page.evaluate(()=>window.__test.mounts)===1,'Share update remounted');return 'beta with encoded password';
  });
  // Measure actual computed Tailwind color/opacity over the composed page background.
  await page.waitForTimeout(250);
  const measureIcon=()=>button(names[2]).locator('svg').evaluate(icon=>{
   const parse=color=>{const v=color.match(/[\d.]+/g).map(Number);return [v[0],v[1],v[2],v[3]??1];};
   const layers=[];let opacity=1;
   for(let e=icon;e;e=e.parentElement){const s=getComputedStyle(e);opacity*=Number(s.opacity);layers.unshift(parse(s.backgroundColor));}
   let bg=[255,255,255];for(const layer of layers)bg=bg.map((x,i)=>layer[i]*layer[3]+x*(1-layer[3]));
   const color=parse(getComputedStyle(icon).color),alpha=color[3]*opacity;
   return {cssColor:getComputedStyle(icon).color,effectiveOpacity:opacity,effectiveAlpha:alpha,background:bg,foreground:bg.map((x,i)=>color[i]*alpha+x*(1-alpha))};
  });
  const metrics=await measureIcon();
  metrics.contrastRatio=ratio(metrics.foreground,metrics.background);
  await check('未选中降级图标对比度至少 3:1',async()=>{assert(metrics.contrastRatio>=3,'Contrast '+metrics.contrastRatio.toFixed(3)+' < 3');return metrics;});
  await button(names[2]).click();
  await page.waitForTimeout(250);
  metrics.selected=await measureIcon();
  metrics.selected.contrastRatio=ratio(metrics.selected.foreground,metrics.selected.background);
  await check('已选中降级图标对比度至少 3:1',async()=>{assert(metrics.selected.contrastRatio>=3,'Selected contrast below 3');return metrics.selected;});
  await button(names[0]).click();
  await page.waitForTimeout(250);
  await tabTo('预览 '+names[2]);
  const focus=await button(names[2]).evaluate(el=>{const s=getComputedStyle(el);return{focused:el===document.activeElement,focusVisible:el.matches(':focus-visible'),outline:s.outline,boxShadow:s.boxShadow};});
  await check('胶卷键盘焦点可见',async()=>{assert(focus.focused&&focus.focusVisible,'Not focus-visible');assert(focus.boxShadow!=='none'||!focus.outline.startsWith('none'),'No focus indicator');return focus;});
  await page.screenshot({path:path.join(taskRoot,`web-${label}-focus.png`),fullPage:true});
  await page.evaluate(()=>window.__test.update('102','v3'));await button(names[1]).locator('svg').waitFor();
  await page.screenshot({path:path.join(taskRoot,`web-${label}-fallback.png`),fullPage:true});
  await check('Escape 关闭回调一次',async()=>{await page.keyboard.press('Escape');assert(await page.evaluate(()=>window.__test.closes)===1,'Escape not consumed exactly once');return 'one close';});
  await check('无未处理 React 浏览器异常',async()=>{assert(errors.length===0,errors.join(';'));return 'none';});
  const evidence={timestamp:new Date().toISOString(),label,browser:browser.version(),source:sourcePath,sourceSha256:crypto.createHash('sha256').update(source).digest('hex'),
   harness:'real React/PreviewModal/Tailwind in Chromium; loopback controlled HTTP; old error is the actual saved React handler invoked with flushSync',
   results,metrics,focus,requests,errors,passed:results.filter(r=>r.pass).length,failed:results.filter(r=>!r.pass).length};
  fs.writeFileSync(path.join(taskRoot,`web-${label}-results.json`),JSON.stringify(evidence,null,2)+'\n');
  console.log(JSON.stringify({passed:evidence.passed,failed:evidence.failed,contrast:metrics.contrastRatio,browser:evidence.browser}));
  process.exitCode=evidence.failed?1:0;
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
