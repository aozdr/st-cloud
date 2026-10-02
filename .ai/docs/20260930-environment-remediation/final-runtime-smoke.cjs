// 最终8080版本使用普通注册接口和内存凭据；不从Redis/数据库提取会话，不提升权限。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const base = 'http://127.0.0.1:8080';
const report = { at:new Date().toISOString(), backend:JSON.parse(fs.readFileSync(path.join(__dirname,'backend-running-final.json'))), checks:[] };
let pair;
function passed(name, details={}) { report.checks.push({name,pass:true,...details}); console.log(JSON.stringify({check:name,...details})); }
async function api(method, url, data, authorized=true) {
 const response = await fetch(base+'/api'+url, {method, headers:{'Content-Type':'application/json',...(authorized?{Authorization:'Bearer '+pair.token}:{})}, ...(data===undefined?{}:{body:JSON.stringify(data)})});
 const body = await response.json();
 assert.equal(response.status,200,url.split('?')[0]+' HTTP'); assert.equal(body.code,200,url.split('?')[0]+' business code '+body.code);
 return body.data;
}
function png() {
 const width=64,height=32, color=crypto.randomBytes(3), pixels=Buffer.alloc(height*(width*4+1));
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){const offset=y*(width*4+1)+1+x*4;color.copy(pixels,offset);pixels[offset+3]=255;}
 function chunk(type,data){const name=Buffer.from(type), content=Buffer.concat([name,data]);let crc=0xffffffff;
  for(const byte of content){crc^=byte;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}
  const out=Buffer.alloc(data.length+12);out.writeUInt32BE(data.length);content.copy(out,4);out.writeUInt32BE((crc^0xffffffff)>>>0,out.length-4);return out;}
 const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=6;
 return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]);
}
async function main(){
 await api('GET','/auth/ping',undefined,false);
 const username='env_final_'+Date.now().toString(36)+crypto.randomBytes(3).toString('hex');
 pair=await api('POST','/auth/register',{username,password:'Fixture!'+crypto.randomBytes(16).toString('hex')},false);
 report.fixture={username}; passed('final_backend_registered_normal_fixture');
 for(let round=1;round<=2;round++){
  const prior=pair.refreshToken; pair=await api('POST','/auth/refresh',{refreshToken:prior},false);
  assert.ok(pair.token&&pair.refreshToken&&pair.refreshToken!==prior);
  const rejected=await fetch(base+'/api/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:prior})});
  assert.equal((await rejected.json()).code,1005); await api('GET','/auth/me');
  passed('final_backend_rotation_'+round,{oldRefreshRejected:true});
 }
 const folder=await api('POST','/file/folder',{parentId:'0',folderName:'final-image-'+username});
 const root=await api('POST','/sync/roots',{cloudFolderNodeId:folder.id,localPathHint:path.join(__dirname,'final-runtime-image')});
 const bytes=png(),md5=crypto.createHash('md5').update(bytes).digest('hex');
 const init=await api('POST','/file/upload/init',{fileName:'s3-image-probe.png',fileSize:bytes.length,fileMd5:md5,totalChunks:1,chunkSize:5242880,parentId:folder.id});
 const query='uploadId='+encodeURIComponent(init.uploadId)+'&s3UploadId='+encodeURIComponent(init.s3UploadId);
 let node;
 if(init.transferMode==='relay'){
  const response=await fetch(base+'/api/file/upload/relay-chunk?'+query+'&seq=1',{method:'POST',headers:{Authorization:'Bearer '+pair.token,'Content-Type':'application/octet-stream'},body:bytes});
  assert.equal((await response.json()).code,200);node=await api('POST','/file/upload/relay-finalize?'+query);
 }else{
  const chunk=await api('GET','/file/upload/chunk-url?'+query+'&chunkIndex=1');
  assert.equal(new URL(chunk.url).origin,'http://127.0.0.1:9000');
  const put=await fetch(chunk.url,{method:'PUT',body:bytes});assert.equal(put.status,200);
  await api('POST','/file/upload/chunk-confirm?'+query+'&chunkIndex=1');
  node=await api('POST','/file/upload/merge',{uploadId:init.uploadId,s3UploadId:init.s3UploadId,fileId:init.fileId});
 }
 report.fixture={...report.fixture,folderId:folder.id,rootId:root.id,nodeId:node.id};
 const original=await fetch(base+'/api/file/'+node.id+'/stream',{headers:{Authorization:'Bearer '+pair.token}});
 assert.equal(original.status,200);assert.ok(Buffer.from(await original.arrayBuffer()).equals(bytes));
 passed('final_backend_s3_image_original_roundtrip',{nodeId:node.id,bytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex')});
 const preview=await api('GET','/preview/'+node.id);assert.equal(preview.type,'image');
 assert.equal(new URL(preview.url).origin,'http://127.0.0.1:9000');
 const thumbnail=await fetch(preview.url);assert.equal(thumbnail.status,200);
 const jpg=Buffer.from(await thumbnail.arrayBuffer());assert.equal(jpg.readUInt16BE(0),0xffd8);
 passed('final_backend_s3_thumbnail_roundtrip',{http:thumbnail.status,contentType:thumbnail.headers.get('content-type'),bytes:jpg.length});
 const {chromium}=require('C:/Users/aoz/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{const page=await browser.newPage();await page.setContent('<img alt="S3 preview probe" src="'+preview.url.replaceAll('&','&amp;')+'">');
  await page.waitForFunction(()=>document.querySelector('img').complete&&document.querySelector('img').naturalWidth>0);
  const image=await page.locator('img').evaluate(img=>({width:img.naturalWidth,height:img.naturalHeight}));
  assert.ok(image.width>0&&image.height>0);await page.screenshot({path:path.join(__dirname,'final-image-preview.png')});
  passed('final_backend_real_chrome_image_decode',image);
 }finally{await browser.close();}
 let journal;
 for(let i=0;i<30;i++){journal=await api('GET','/sync/roots/'+root.id+'/delta?since=0');if(journal.changes.some(row=>row.nodeId===node.id))break;await new Promise(resolve=>setTimeout(resolve,500));}
 assert.ok(journal.changes.some(row=>row.nodeId===node.id));passed('final_backend_mq_journal',{cursor:journal.cursor,nodeId:node.id});
 pair=null;report.status='passed';fs.writeFileSync(path.join(__dirname,'final-runtime-smoke.json'),JSON.stringify(report,null,2));
}
main().catch(error=>{pair=null;report.status='failed';report.error=error.message;fs.writeFileSync(path.join(__dirname,'final-runtime-smoke.json'),JSON.stringify(report,null,2));console.error('FINAL_SMOKE_FAIL '+error.message);process.exitCode=1;});
