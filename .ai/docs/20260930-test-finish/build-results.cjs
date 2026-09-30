// 当前任务只读继承历史逐项证据，新增完整验证另写本目录。
const fs=require('node:fs'),path=require('node:path');
const baseline=fs.readFileSync(path.join(__dirname,'../20260927-test-resume/results.md'),'utf8');
const updates={
 'TC04-02':'当前SyncServiceProjectionTest含MOVE/RENAME排除双向、同路径脏事件与相似前缀；desktop-regression.log实际chokidar新建/改名排除子树无上传，普通文件正常上传；backend-xml对应Projection报告。',
 'TC04-14':'desktop-regression.log：MOVE/RENAME×源存在/源缺失/目标被占用及目录补齐，源缺失下载失败保留状态/cursor、恢复成功才删除旧映射，watcher自身事件抑制另有完整回归。',
 'TC04-15':'desktop-regression.log：旧路径X且mtime未改，失败保护X，独立进程恢复后N/X位置与内容各自正确。',
 'TC04-16':'desktop-regression.log：未知身份原件保留不绑定N，直接消费同路径脏MOVE不能改绑X，失败保留基线、独立进程恢复。',
 'TCDB-04':'large-id-schema.xml 4项、schema-consistency.xml 3项；core/auth/team实际H2 DDL的BIGINT/NOT NULL/默认值及三组大ID精确存读、安全版本到Long.MAX。MySQL结构门禁仍由execution-20260925原full-migration-probe前后两次compare证据支持，H2与MySQL分开记录。',
 'TCDB-06':'large-id-schema.xml：旧Integer Jackson数字/字符串0/1/2兼容，三组大ID均反序列化拒绝及Math.toIntExact拒绝溢出；历史db-chaos.log实际MySQL缩列失败原大角色值保留。旧类型契约夹具，不宣称运行旧发行包；回退保留BIGINT且不重分配角色。',
 'TCDB-07':'id-contract.xml：三组大ID全真实响应DTO字段及邀请请求精确；browser-matrix.log三次轮换空间/角色/成员/邀请/ACL及复制目录请求；browser-version-share-complete.log三组文件/版本/分享目录与节点/路由/query，实际UI与API受控；desktop-id-config.log实际引擎root/node/cursor消费，SQLite三组存读/迁移另见历史sqlite-migration-complete-rerun.log与当前桌面回归。分层证据，非同一HTTP全链路。',
 'TCDB-11':'jwt-release.xml及backend-xml Redis矩阵：真实Redis+H2 TCP、第二JVM拒绝旧无版本Access/Refresh，重新登录可用、改密后两实例拒绝旧会话；仅验签旧行为模拟仍接受撤销令牌，未运行旧发行包。排空旧行为前不得声称撤权保证。',
 'TCDB-12':'backend-xml SearchProcess：真实进程缺共享密钥/无效密钥启动失败；desktop-id-config.log缺失/旧/错误scopeProjectionVersion不推进；browser-matrix.log无效角色、预览降级和一次重试；thumbnail-config.xml缺阈值使用20MiB/1600万像素/并发2安全默认，0/-1初始化失败，定制阈值实际拒绝。完整集合为发布演练，不是部署验收。'
};
const rows=[...baseline.matchAll(/^\| \[(TC[^\]]+)\]\(([^)]+)\) \| (PASS|FAIL|PARTIAL|NOT RUN) \| (.+) \|$/gm)].map(([,id,link,status,note])=>({id,link,status,note}));
if(rows.length!==136)throw Error('用例数非136');
for(const row of rows)if(updates[row.id]){row.status='PASS';row.note=updates[row.id];}
const counts=Object.fromEntries(['PASS','FAIL','PARTIAL','NOT RUN'].map(s=>[s,rows.filter(r=>r.status===s).length]));
fs.writeFileSync(path.join(__dirname,'results.md'),'# 2026-09-30逐项验收证据\n\n共136条：'+JSON.stringify(counts)+'。\n\n未更新项引用[前次逐项记录](../20260927-test-resume/results.md)及其原始日志，本轮不改写历史；更新项日志均位于本目录。状态是逐项结论，整体回归/自检/流程验收另见testreport.md与acceptance.md。\n\n| 编号 | 状态 | 证据或边界 |\n|---|---|---|\n'+rows.map(r=>`| [${r.id}](${r.link}) | ${r.status} | ${r.note} |`).join('\n')+'\n');
fs.writeFileSync(path.join(__dirname,'remaining.md'),'# 未完成逐项验收\n\n'+(rows.filter(r=>r.status!=='PASS').map(r=>`${r.id}: ${r.status}`).join('\n')||'逐项无剩余。整体完成仍须回归、自检和当前State验收。')+'\n');
console.log(counts);
