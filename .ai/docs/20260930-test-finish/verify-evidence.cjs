const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const base=__dirname,root=path.resolve(base,'../../..');
const read=p=>fs.readFileSync(path.join(base,p),'utf8').replace(/^\uFEFF/,'');
const assert=(v,m)=>{if(!v)throw Error(m)};
const rows=[...read('results.md').matchAll(/^\| \[(TC[^\]]+)\].*? \| (PASS|FAIL|PARTIAL|NOT RUN) \|/gm)];
assert(rows.length===136&&new Set(rows.map(r=>r[1])).size===136,'136条唯一编号');
assert(rows.every(r=>r[2]==='PASS'),'存在未通过项');
const summary=JSON.parse(read('backend-summary.json'));
assert(summary.every(s=>s.failures===0&&s.errors===0),'后端失败');
assert(summary.reduce((n,s)=>n+s.tests,0)===732,'回归数变化');
assert(summary.reduce((n,s)=>n+s.skipped,0)===19,'条件跳过数量');
for(const [file,count] of [['share-mysql-s3.xml',19],['id-contract.xml',3],['thumbnail-config.xml',12],['jwt-release.xml',1]]) {
 const xml=read(file),suite=xml.match(/<testsuite\b[^>]*>/s)[0];
 const attr=name=>Number(suite.match(new RegExp(name+'="([^"]+)"'))[1]);
 assert(attr('tests')===count&&attr('failures')===0&&attr('errors')===0&&attr('skipped')===0,file+'非完整通过');
}
const desktop=read('desktop-regression.log');
assert(desktop.includes('pass 21')&&desktop.includes('pass 126')&&!desktop.includes('fail 1'),'桌面回归');
assert(read('desktop-id-config.log').includes('pass 9'),'桌面ID/协议');
assert((read('browser-matrix.log').match(/PASS:/g)||[]).length===7,'浏览器矩阵');
assert(read('browser-version-share-complete.log').includes('PASS 三组大ID'),'历史/分享');
assert(read('desktop-types.log').trim()==='> st-cloud-desktop@1.0.0 lint\n> tsc --noEmit'||!read('desktop-types.log').includes('error TS'),'桌面类型');
assert(read('desktop-build.log').includes('Build success'),'桌面构建');
assert(read('web-build.log').includes('built in'),'前端构建');
const hashes={};
const walk=directory=>{for(const d of fs.readdirSync(directory,{withFileTypes:true})){const p=path.join(directory,d.name);if(d.isDirectory())walk(p);else if(/\.(java|ts|tsx|cjs|sql)$/.test(d.name))hashes[path.relative(root,p).replace(/\\/g,'/')]=crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');}};
for(const module of fs.readdirSync(root).filter(n=>n.startsWith('st-'))) {const src=path.join(root,module,'src');if(fs.existsSync(src))walk(src);}
hashes['.ai/schema/loop-state.schema.json']=crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'.ai/schema/loop-state.schema.json'))).digest('hex');
fs.writeFileSync(path.join(base,'validated-source-hashes.json'),JSON.stringify(hashes,null,2));
console.log('PASS 136条唯一编号、732项后端及19条件补跑、3契约、147桌面+9定向、浏览器与构建证据；已记录源码SHA256。');
