// 将用例定义与本轮真实证据对应；未覆盖的编号保持 NOT RUN。
const fs = require('node:fs');
const path = require('node:path');
const folder = path.resolve(__dirname, '..');
const files = ['CR-01.md', 'CR-02.md', 'CR-03.md', 'CR-04.md',
  'CR-05.md', 'CR-06.md', 'CR-07.md', 'DB-ID.md'];
const evidence = new Map();

function mark(ids, status, note) {
  for (const id of ids.split(' ')) {
    if (evidence.has(id)) throw Error('重复设置执行状态: ' + id);
    evidence.set(id, { status, note });
  }
}

mark('TCDB-08', 'PASS', 'desktop-probes.cjs：裸数字/字符串大 ID、缺失与损坏 JWT 均按预期；实际 api-client.ts。');
mark('TCDB-01 TCDB-02', 'PASS', 'migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。');
mark('TCDB-03', 'PASS', 'full-migration-probe.ps1：完整 02～43 初始化、迁移前 compare-schema 退出 1、原 44/45、测试版 schema_version 登记、迁移后 compare-schema 退出 0。');
mark('TC04-30', 'FAIL', 'desktop-probes.cjs：真实临时文件 V1 校验后改为 V2，源被删除，恢复副本仅有 V1。');
mark('TC04-15 TC04-16 TC04-17 TC04-18 TC04-21 TC04-22 TC04-23 TC04-26',
  'PARTIAL', 'desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。');
mark('TC01-01', 'PARTIAL', 'AuthServiceIntegrationTest.login_disabledUser_rejected：仅覆盖禁用登录，未覆盖提交后旧令牌撤权。');
mark('TC01-11', 'PARTIAL', 'AuthServiceIntegrationTest.refreshToken_rotatesWhenStored：仅单请求轮换，未覆盖 Redis CAS 并发。');
mark('TC01-22', 'PARTIAL', 'AuthServiceIntegrationTest.register_createsUserAssignsDefaultRoleAndIssuesValidToken：仅成功注册。');
mark('TC02-06', 'PARTIAL', 'TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：仅禁用角色变体。');
mark('TC02-07', 'PARTIAL', 'TeamServicePermissionIntegrationTest.customRolePermissionsApplied：验证自定义 view+upload，未覆盖 view-only 拒绝。');
mark('TC02-21', 'PARTIAL', 'TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：未遍历各授权入口。');
mark('TC05-03', 'PARTIAL', 'ShareServiceImplSecurityIntegrationTest.allowDownloadZeroStreamRejected：仅下载开关变体。');
mark('TC05-04', 'PARTIAL', 'ShareServiceImplSecurityIntegrationTest.streamShareFileRejectsSamePrefixSibling：仅同名前缀越界变体。');
mark('TC05-09', 'PARTIAL', 'ShareServiceImplNoTransactionIntegrationTest.getDownloadUrl_doesNotOpenTransaction：不限额 URL 两次计数，未覆盖并发和流。');
mark('TC05-12', 'PARTIAL', 'ShareServiceImplSecurityIntegrationTest.streamShareFileRateLimitedTo5MBps：未验证事务边界及全部文件场景。');
mark('TC06-01', 'PARTIAL', 'TeamSearchServiceTest.consecutiveCursorPagesDoNotRepeatVisibleRecords：仅单实例分页。');
mark('TC06-03', 'PARTIAL', 'TeamSearchServiceTest.missingCursorSecretFailsFast 与 st-api 缺配置启动失败：未验证纯空白变体。');
mark('TC06-05', 'PARTIAL', 'TeamSearchServiceTest.tamperedCursorIsRejectedAndEsIsNotCalled：未覆盖不同密钥和所有非法编码。');
mark('TC06-06', 'PARTIAL', 'TeamSearchServiceTest.cursorIsBoundToPrincipalAndAllQueryConditions：测试数个绑定维度，未覆盖全部组合。');
mark('TC06-07', 'PARTIAL', 'TeamSearchServiceTest.expiredCursorIsRejectedBeforeEs：未覆盖到期临界点与跨实例。');
mark('TC07-01', 'PARTIAL', 'PreviewServiceIntegrationTest.previewImage_generatesThumbnailAndReturnsImageUrl：仅一个受支持格式/尺寸。');
mark('TCDB-04', 'PARTIAL', 'SchemaConsistencyTest 3 项、auth/team H2 测试及 MySQL 列对比通过；未覆盖所有大 ID 存读子场景。');
mark('TCDB-09', 'PARTIAL', 'st-desktop db-migrate.test.ts：大 ID 存读和二次迁移通过；未覆盖该用例列出的所有 ID/cursor 列。');

const rows = [];
for (const file of files) {
  const source = fs.readFileSync(path.join(folder, file), 'utf8');
  for (const match of source.matchAll(/^\| (TC(?:0[1-7]|DB)-\d{2}) \/ ([^|]+) \|/gm)) {
    const id = match[1];
    rows.push({ id, file, ...evidence.get(id) || {
      status: 'NOT RUN', note: '本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。',
    } });
  }
}
if (rows.length !== 136) throw Error('用例数量变化: ' + rows.length);
for (const id of evidence.keys()) if (!rows.some(row => row.id === id)) throw Error('证据编号不存在: ' + id);
const counts = Object.fromEntries(['PASS', 'FAIL', 'PARTIAL', 'NOT RUN'].map(status =>
  [status, rows.filter(row => row.status === status).length]));

let markdown = `# 2026-09-25 测试执行记录\n\n`;
markdown += `代码：HEAD 912996e1b8f34904b792f29ceacab2023844e038 + 当前未提交整改；执行者：主线程。状态按 [用例总册](../testcases.md) 的完整预期判定。\n\n`;
markdown += `总计 136 条：PASS ${counts.PASS}，FAIL ${counts.FAIL}，PARTIAL ${counts.PARTIAL}，NOT RUN ${counts['NOT RUN']}。PARTIAL 表示只完成部分场景或隔离探针，**不得算通过**。\n\n`;
markdown += `## 核心失败\n\n`;
markdown += `**TC04-30（P0，数据丢失）**：[sync-recovery.ts](../../../../st-desktop/src/sync/sync-recovery.ts) 在完成源文件与恢复副本的哈希核对后，调用 markWritten，再直接 unlink 源文件。探针在该回调中将源文件从 \`original-v1\` 写成 \`user-edited-v2\`；运行后源文件已消失，恢复副本仍是 \`original-v1\`。因此备份验证与删除之间的本地编辑会丢失。探针使用真实临时目录，全部测试文件已清理。后续需要在删除前重新证明源内容仍与副本一致，或采用能保证原子归属的保全流程。\n\n`;
markdown += `## 已执行的套件与环境检查\n\n`;
markdown += `- Java：首轮 \`mvn -q test -DskipITs\` 中，st-common/auth/core/share/team/sync/search/preview/admin 的当日 Surefire 报告共 481 项、0 失败、0 错误、0 跳过，其中 SchemaConsistencyTest 3 项通过。全命令退出码为 1，因为 st-api.ReindexIntegrationTest 完整上下文缺少搜索游标密钥。\n`;
markdown += `- st-api 不能计通过：加测试专用密钥的重跑在到达 st-api 前主动停止，因为该测试会调用真实 \`reindexAll\`，当前没有隔离数据库和 Elasticsearch；没有执行该全量重建。\n`;
markdown += `- 桌面 \`npm test\` 和 \`npm run test:round3\`：各 19 项通过，属于重叠套件，不相加为 38 项。\`npx tsc --noEmit\` 退出码 0。\n`;
markdown += `- Web \`npm run build\` 退出码 0；\`node src/lib/hash-contract.test.mjs\` 6 项通过。\n`;
markdown += `- 现有开发库只读检查：\`compare-schema.ps1\` 退出码 0，19 个 H2 表的共有列对齐，迁移清单无未登记 SQL；列查询确认两列 role 为 BIGINT NOT NULL DEFAULT 2，security_version 为 BIGINT NOT NULL DEFAULT 0，schema_version 记录 20260924.1 与 44/45 SQL。未向现有开发库写入。\n`;
markdown += `- 隔离迁移：[migration-probe.ps1](migration-probe.ps1) 在本轮临时 MySQL 8.0.46 容器（仅 127.0.0.1:13306/stcloud_test）对合成旧结构实际执行原 44/45 SQL。旧值与异常引用、大 ID、默认值、备注、存量及新用户安全版本通过。两个临时容器与过渡 SQL 副本已清理，现有 stcloud 库未被写入。\n`;
markdown += `- 完整升级：[full-migration-probe.ps1](full-migration-probe.ps1) 在另一个隔离容器的内部 stcloud 库跑通 02～43 初始化。迁移前结构对比退出 1（缺 security_version 与待登记 SQL）；执行原 44/45 并登记仅用于隔离测试的 20260925.1 后，结构对比退出 0。初次尝试误用内部库名 stcloud_test，因历史 SQL 固定 USE stcloud 而失败；调整隔离容器内部库名后成功。所有临时容器与过渡 SQL 副本已清理，宿主现有 3306 库未写入。\n`;
markdown += `- [桌面专项探针](desktop-probes.cjs)：10 个场景中 9 个探针断言通过，TC04-30 失败。前九个以当前 TypeScript 源码配合内存 FS/API/DB 执行；它们只覆盖各编号的一部分，见下表。\n\n`;
markdown += `## 全部用例状态\n\n| 编号 | 状态 | 本轮证据或未执行原因 |\n|---|---|---|\n`;
for (const row of rows) markdown += `| [${row.id}](../${row.file}) | ${row.status} | ${row.note.replaceAll('|', '\\|')} |\n`;
markdown += `\n## 执行边界\n\n未运行的用例需要合成租户/团队/文件夹具、可控制的事务屏障、隔离的 Redis/MySQL/S3/Elasticsearch 或浏览器端到端环境。当前可访问的本机服务连着开发数据，不能把破坏性迁移、重建索引、真实删除/并发写入当成隔离测试执行。既有单元/集成套件通过不代表这 136 条均通过。\n`;
fs.writeFileSync(path.join(__dirname, 'results.md'), markdown);
console.log(JSON.stringify({ rows: rows.length, counts }));
