// 在首轮逐项执行记录上叠加本轮的新证据；保留原始报告以便追溯。
const fs = require('node:fs');
const path = require('node:path');
const prior = fs.readFileSync(path.resolve(__dirname,
  '../20260924-code-review-testcases/execution-20260925/results.md'), 'utf8');
const updates = new Map(Object.entries({
  'TC04-30': ['PASS', '原探针 10/10；本轮真实文件恢复 11/11，含最后时刻改写及重叠拒绝。'],
  'TC04-08': ['PASS', 'sync-cursor.test.cjs：数字/非法游标、损坏 changes/hasMore、缺失/错误 v2、hasMore 不前进均拒绝且不固化游标。'],
  'TC05-01': ['PARTIAL', 'ShareServiceImplNoTransactionIntegrationTest：H2 真 Mapper、五线程在源流屏障并发，只有一份文件字节、count=1；待真实 MySQL 验证。'],
  'TC05-02': ['PARTIAL', 'ShareServiceImplNoTransactionIntegrationTest：一个 URL 与四个流在 H2 单实例同时争最后一次额度，仅一个成功；待双实例 MySQL 验证。'],
  'TC05-05': ['PASS', 'ShareServiceImplNoTransactionIntegrationTest：源流打开失败、URL 签名失败均抛错，count=0 且无文件响应。'],
  'TC05-06': ['PASS', 'ShareServiceImplNoTransactionIntegrationTest：源流打开后另一 URL 先授予；当前流被拒绝并关闭，响应输出流与文件长度头均未设置，count=1。'],
  'TC07-01': ['PARTIAL', 'ThumbnailRendererTest：真实 PNG 转可解码 JPEG 且尺寸正确；尚未覆盖所有格式和尺寸入口。'],
  'TC07-02': ['PARTIAL', 'ThumbnailRendererTest：WebP/SVG 渲染入口预先拒绝；普通主图浏览器展示未验证。'],
  'TC07-03': ['PARTIAL', 'ThumbnailRendererTest：HEAD 对 B-1/B/B+1 边界检查通过；预览服务缓存写入链未覆盖。'],
  'TC07-04': ['PARTIAL', 'ThumbnailRendererTest：HEAD 小、实际流超限停止且关闭；缺长度与对象增长变体未覆盖。'],
  'TC07-06': ['PARTIAL', 'ThumbnailRendererTest：伪装 jpg 的非图片被拒；损坏/截断头和资源释放全变体未覆盖。'],
  'TC07-07': ['PASS', 'ThumbnailRendererTest：C=1，闩锁占用第一个许可，第二请求在读取前拒绝，释放后第三请求成功。'],
  'TC07-08': ['PARTIAL', 'ThumbnailRendererTest：读取 IOException 后流关闭、许可释放，下一请求成功；其他故障阶段未覆盖。'],
  'TC07-09': ['PASS', 'ThumbnailRendererTest：Spring 启动对 B/P/C 的 0/负数六变体均失败；自定义低上限启动后实际生效。'],
  'TCDB-10': ['PASS', 'sync-cursor.test.cjs：相邻大游标、真实 SQLite 持久化重开后继续分页；hasMore 不前进拒绝且不改库。'],
}));
const rows = [];
for (const line of prior.split(/\r?\n/)) {
  const match = line.match(/^\| \[(TC(?:0[1-7]|DB)-\d{2})\]\(\.\.\/([^)]*)\) \| (PASS|FAIL|PARTIAL|NOT RUN) \| ([^|]*) \|$/);
  if (!match) continue;
  const [, id, file, oldStatus, oldNote] = match;
  const [status, note] = updates.get(id) || [oldStatus, oldNote];
  rows.push({ id, file, status, note });
}
if (rows.length !== 136 || [...updates.keys()].some(id => !rows.some(row => row.id === id))) {
  throw Error('用例基线或新增证据编号不匹配');
}
const count = status => rows.filter(row => row.status === status).length;
let content = '# 2026-09-25 续测逐项结果\n\n';
content += `基于[原执行记录](../20260924-code-review-testcases/execution-20260925/results.md)和[上轮失败修复](../20260925-test-fix/results.md)。共 136 条：PASS ${count('PASS')}，FAIL ${count('FAIL')}，PARTIAL ${count('PARTIAL')}，NOT RUN ${count('NOT RUN')}。PARTIAL 不算通过。\n\n`;
content += '本轮发现并修复了数字游标被 String() 接受导致潜在精度丢失的问题；服务端 Jackson 将 Long 输出为字符串。合法字符串大游标仍保留原值。\n\n';
content += '## 本轮验证\n\n';
content += '- 全量 `mvn -q test -DskipITs`：10 模块 495 项，0 失败、0 错误、0 跳过；[模块明细](java-counts.json)与[原始日志](maven-full.log)。\n';
content += '- Maven 定向 `ThumbnailRendererTest`：8/8，零失败、零错误、零跳过；实际 ImageIO、受控流和 Spring 上下文。\n';
content += '- Maven 定向 `ShareServiceImplNoTransactionIntegrationTest`：5/5；五流及 URL+四流竞争均只授予一次，源流/签名失败与额度抢尽的副作用也已验证。\n';
content += '- 桌面 `npm test`：既有 19 项 + 同步专项 17 项通过；`npx tsc --noEmit` 与 `npm run build:main` 退出码均为 0。\n';
content += '- 测试夹具首次重复注册 S3 Bean 导致定向测试失败；移除重复注册后通过。该失败由测试配置引起，生产代码未因此修改。\n';
content += '- 分享定向测试首次因既有固定分享码在两个非事务用例中重复而失败；改为按文件 ID 唯一后通过。\n';
content += '- [缩略图测试](../../st-core/src/test/java/com/stcloud/core/service/ThumbnailRendererTest.java)和[游标测试](../../st-desktop/src/sync/sync-cursor.test.cjs)均接入仓库测试路径。\n\n';
content += '## 全部用例\n\n| 编号 | 状态 | 证据或缺口 |\n|---|---|---|\n';
for (const row of rows) content += `| [${row.id}](../20260924-code-review-testcases/${row.file}) | ${row.status} | ${row.note} |\n`;
content += `\n本轮仍缺少隔离的多实例、Redis/MySQL/S3/Elasticsearch 和浏览器端到端夹具。未运行的 ${count('NOT RUN')} 条不代表失败或通过。\n`;
fs.writeFileSync(path.join(__dirname, 'results.md'), content);
console.log({ total: rows.length, PASS: count('PASS'), FAIL: count('FAIL'), PARTIAL: count('PARTIAL'), NOT_RUN: count('NOT RUN') });
