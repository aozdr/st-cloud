# 2026-09-25 剩余用例执行记录

基于[前一轮逐项结果](../20260925-test-continue/results.md)。共 136 条：PASS 41，FAIL 0，PARTIAL 50，NOT RUN 45。PARTIAL 不算通过。

## 本轮证据

- [MySQL 故障与回退演练日志](db-chaos.log)：使用唯一命名的临时容器和测试夹具，结束后已清理；未操作共享/生产库。
- `ShareServiceImplNoTransactionIntegrationTest`：H2 真实 Mapper，13 项零失败；覆盖流中断、无限额计数、无效分享/节点、缩略图失败、URL 重试、DB 授予故障与占位前状态变更。
- `TeamSearchServiceTest`：19 项零失败；新增跨 service 对象共享/异密钥游标分页与 Spring Bean 配置启动检查。
- `ThumbnailRendererTest`：11 项零失败；原版缺失 HEAD 长度触发 NullPointerException，现由实际读取字节硬上限受控拒绝；像素边界和溢出头亦通过。
- 桌面 `sync-cursor.test.cjs`：8 项零失败；新增空过滤页继续与第二页故障后的真实 SQLite 重启续传。
- `UserSecurityServiceIntegrationTest` 2 项及 `JwtAuthenticationFilterPurposeTest` 4 项零失败；H2 主库边界与过滤器用途/异常清理。
- `TeamServicePermissionIntegrationTest` 新增空角色四组合通过，定向测试零失败。
- `TeamControllerCopyPermissionTest` 4 项零失败；入口复制前逐一校验源 view 与目标 upload，两种批量顺序均无提前写调用。
- `FileServiceFlowIntegrationTest`：新增未完成源/批量后续源/目录子项拒绝；[首次复现](team-copy-incomplete-repro.log)显示非法副本已插入，修复后定向测试通过。
- 团队配额的文件及目录复制在 H2 中均先拒绝；[首次复现](team-copy-quota-repro.log)显示旧实现先插入副本，现有预检且并发扣减保留。
- [桌面测试日志](desktop-test.log)：标准 `npm test` 20+69=89 项零失败，新增 37 项实际引擎、落盘 SQLite 与进程重启/页面重放矩阵；`npm run lint` 与 `npm run build:main` 通过。已修复[引擎跳过 pending](sync-restart-repro.log)和[恢复目录联接重定向](recovery-link-repro.log)。历史复现还包括[路径](sync-path-repro.log)、[目录列举](sync-reconcile-repro.log)、[恢复模块重试](sync-recovery-retry-repro.log)。
- 服务端 SyncServiceProjectionTest：10 项零失败，覆盖16种移动投影组合、排除边界、501条过滤分页和对账标记；证据 sync-projection.log 及 Surefire 报告。
- [全量 Maven 输出](maven-full.log)及[模块计数](java-counts.json)：534 项，0 失败、0 错误、0 跳过。

- [未执行夹具清单](remaining.md)：逐组列出 45 条 NOT RUN 所需的独立证据。

## 全部用例

| 编号 | 状态 | 证据或缺口 |
|---|---|---|
| [TC01-01](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.login_disabledUser_rejected：仅覆盖禁用登录，未覆盖提交后旧令牌撤权。 |
| [TC01-02](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-03](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-04](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-05](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-06](../20260924-code-review-testcases/CR-01.md) | PARTIAL | UserSecurityServiceIntegrationTest：H2 真 Mapper 对错租户、不存在/禁用/逻辑删除用户及禁用/逻辑删除租户均拒绝；未通过真实 HTTP 过滤器串联全部变体。 |
| [TC01-07](../20260924-code-review-testcases/CR-01.md) | PARTIAL | UserSecurityServiceIntegrationTest：缺失、负值、BigInteger 溢出、小数、数字字符串 securityVersion 均拒绝，合法版本通过；尚未对 Access/Refresh 两种签名令牌完整串联。 |
| [TC01-08](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-09](../20260924-code-review-testcases/CR-01.md) | PARTIAL | JwtAuthenticationFilterPurposeTest：Refresh/未知 type 普通 HTTP 拒绝；download/editor 仅绑定节点流端点通过；Redis 为替身，WS 和期限未覆盖。 |
| [TC01-10](../20260924-code-review-testcases/CR-01.md) | PARTIAL | JwtAuthenticationFilterPurposeTest：下游抛异常后清理 UserContext/TenantContext/SecurityContext，下一匿名请求不继承；未联动真实 Access 签名和过期。 |
| [TC01-11](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.refreshToken_rotatesWhenStored：仅单请求轮换，未覆盖 Redis CAS 并发。 |
| [TC01-12](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-13](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-14](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-15](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-16](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-17](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-18](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-19](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-20](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-21](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-22](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.register_createsUserAssignsDefaultRoleAndIssuesValidToken：仅成功注册。 |
| [TC02-01](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-02](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-03](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest：成员在两个相邻大 ID 角色间切换后目录 ACL 结果反转；前端保存后回显尚未浏览器验证。 |
| [TC02-04](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-05](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-06](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：H2 真 Mapper 对不存在、停用、逻辑删除、他空间及他租户的大 ID 角色逐一拒绝成员分配，原成员角色保持 2。 |
| [TC02-07](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.customRolePermissionsApplied：验证自定义 view+upload，未覆盖 view-only 拒绝。 |
| [TC02-08](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：H2 真 Mapper 将大 ID 有效空角色赋给成员且无 ACL；checkPermission 两个重载分别以 null 与普通等级为最低要求，四组合全部拒绝。 |
| [TC02-09](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：大 ID 有效空角色叠加成员目录 ACL 后 view/upload 均通过；角色停用但 ACL 保留时授权直接拒绝。 |
| [TC02-10](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-11](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：创建链接后分别停用/删除角色、令链接过期/撤销，再以邀请人接受四变体均拒绝，未新增成员。 |
| [TC02-12](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：大 ID 角色被成员或有效邀请引用时删除均拒绝且角色保留；成员改回预设、邀请撤销后可删，仅过期邀请引用也可删。 |
| [TC02-13](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-14](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：拥有者改为非管理员、修改/删除 0/1/2 预设角色、修改/删除他空间角色均拒绝；原成员角色及自定义角色名称不变。 |
| [TC02-15](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-16](../20260924-code-review-testcases/CR-02.md) | PASS | TeamServicePermissionIntegrationTest：相邻超安全 ID 9007199254740993/995 的 role ACL 只授权本角色目录；切换后结果反转，旧等级仍为小整数 2。 |
| [TC02-17](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-18](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-19](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-20](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-21](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：未遍历各授权入口。 |
| [TC03-01](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：目标 upload 拒绝时源 view 已查、复制服务及活动日志完全未调用；未用真实 DB 比对节点/引用/用量。 |
| [TC03-02](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：源仅要求 view，目标 upload 通过后才调用复制；实际节点、引用/容量未由此测试验证。 |
| [TC03-03](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：目标根 0 明确要求 upload，拒绝时无复制/活动调用；null 根与真实授权未覆盖。 |
| [TC03-04](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：两种 [F1,F2] 顺序，只要 F2 view 拒绝，整个批次不调用复制或活动；FileServiceFlowIntegrationTest 另证后续源未完成时前置拒绝且无节点写入。真实事件/用量未全覆盖。 |
| [TC03-05](../20260924-code-review-testcases/CR-03.md) | PASS | FileServiceFlowIntegrationTest：他空间源/目标、回收站源、未完成上传源及目录子项、普通文件目标均拒绝；节点/对象/用户用量与事件快照不变。首次复现非法副本残留，修复后通过。 |
| [TC03-06](../20260924-code-review-testcases/CR-03.md) | PASS | FileServiceFlowIntegrationTest：H2 真实 Mapper 的文件和嵌套目录分别超团队配额时，复制前拒绝；节点、对象、用量和成功事件均不增加。首次复现先插入非法副本，修复后通过。 |
| [TC03-07](../20260924-code-review-testcases/CR-03.md) | PARTIAL | FileServiceFlowIntegrationTest：H2 真实 Mapper 批量复制双目录与嵌套子树、同名目标生成 (1)、三个新节点各发布索引事件；单文件对象引用、容量与同步消费未在此场景串联。 |
| [TC03-08](../20260924-code-review-testcases/CR-03.md) | PARTIAL | TeamControllerCopyPermissionTest：提交时目标 upload 被拒绝，服务端入口不执行复制；UI 打开后撤权与重复提交未浏览器联动。 |
| [TC03-09](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-01](../20260924-code-review-testcases/CR-04.md) | PASS | SyncServiceProjectionTest：真实 delta 服务逻辑，受控 Mapper 日志；文件/目录 × MOVE/RENAME × 四种根边界共16组合，类型、相对路径、状态和大ID字符串逐项断言。 |
| [TC04-02](../20260924-code-review-testcases/CR-04.md) | PARTIAL | SyncServiceProjectionTest：普通与排除路径双向投影、排除后代及相似前缀不误匹配通过；实际目录引擎不列举排除子树。同路径改名和 watcher 上传链尚未完整覆盖。 |
| [TC04-03](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-04](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际引擎+SQLite，移入101文件、两层子树、空目录和排除目录；第二页失败不提交，独立进程恢复仅补缺失文件，最终内容及游标正确。 |
| [TC04-05](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：父目录mtime等于基线但子文件已修改，移出后已修改及未跟踪字节完整保存在恢复树，无反向上传，根B不变。 |
| [TC04-06](../20260924-code-review-testcases/CR-04.md) | PASS | SyncServiceProjectionTest：501条原始日志中前500条范围外，空页仍返回扫描游标和hasMore，下一页有效事件不丢；sync-cursor.test.cjs验证客户端空页续传及完成后提交。 |
| [TC04-07](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际 SyncEngine+落盘 SQLite；同页第二事件下载失败，首事件文件/绑定持久化但页游标不变；新进程重放首事件无新下载或写入，第二事件完成后提交，根 B 快照不变。 |
| [TC04-08](../20260924-code-review-testcases/CR-04.md) | PASS | sync-cursor.test.cjs：数字/非法游标、损坏 changes/hasMore、缺失/错误 v2、hasMore 不前进均拒绝且不固化游标。 |
| [TC04-09](../20260924-code-review-testcases/CR-04.md) | PARTIAL | SyncServiceProjectionTest：缺失/空白 oldPath 和根节点移动均返回 reconcileRequired 且无删除事件；实际客户端根改名的完整成功/失败链尚未串联。 |
| [TC04-10](../20260924-code-review-testcases/CR-04.md) | PASS | sync-path/recovery/restart.test.cjs：合法路径正常；实际引擎+SQLite 对当前路径/oldPath 的 ../、盘符、目录联接及恢复目录联接七组合拒绝，根外哨兵/源文件/状态/游标保持不变。修复恢复目录联接重定向写入缺陷，复现见 recovery-link-repro.log。 |
| [TC04-11](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-12](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-13](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：旧 DELETE 分别对应已返回的同节点 N 和占用原路径的新节点 X；实际引擎保留原字节及正确节点绑定，无下载/恢复搬移，完成页游标。 |
| [TC04-14](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-15](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-16](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-17](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：已改目录移动后第二页失败，恢复树完整、目标pending及旧cursor持久化；新进程只补4个缺失文件，最终synced且根B不变。 |
| [TC04-18](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：源目录不存在、第二页失败后分别同引擎与新进程续传完成；其他节点占用目标时拒绝且原字节/绑定/游标不变。 |
| [TC04-19](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：真实 SQLite 旧快照 N 在全量扫描时被当前同路径 X 替换；实际清理环节重读占用者，保留 X 内容/状态，无恢复搬移。 |
| [TC04-20](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-21](../20260924-code-review-testcases/CR-04.md) | PASS | sync-size.test.cjs：真实文件流对 delta 与目录列表的 "3"、"0"、null 分别下载并校验 3/0/3 字节；负数、非数字、超安全整数和小数在两入口均拒绝且无文件写入；相邻大 nodeId/cursor 全程字符串。 |
| [TC04-22](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：实际引擎/下载/SQLite，历史 V1 日志与当前 V2 详情/流分别同页、跨页及同/不同大小四组合，均只下载一次 V2，精确提交最终大游标。 |
| [TC04-23](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：详情后流变化的等长/变长 V3、稳定元数据下截断/篡改四组合均不覆盖原件/不提交游标；重启刷新详情后正确下载并提交，根 B 快照不变。 |
| [TC04-24](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-conflict.test.cjs：真实上传模块对 failed/running 超时均抛错且旧 nodeId/MD5 不被改写；真实临时文件 keep_both 两类失败均保留上传源、原文件，冲突 history 为 error。未联动引擎游标及真实任务服务。 |
| [TC04-25](../20260924-code-review-testcases/CR-04.md) | PARTIAL | sync-conflict.test.cjs：local_wins/latest_wins 本地较新时上传失败均向上传播，原字节不变；模拟恢复后两策略重试同一源成功。未联动任务服务和引擎 cursor。 |
| [TC04-26](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-27](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-28](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-29](../20260924-code-review-testcases/CR-04.md) | PASS | sync-recovery.test.cjs + sync-restart.test.cjs：当前原子移动实现的初始清单写、源移动、complete 写/提交阶段故障及独立进程退出/恢复；DELETE、历史日志、全量清理均先完成 pending 再删状态/提交游标，副本缺失时拒绝确认，根 B 不变。已取消的 copy/hash/unlink/rmdir 映射为原子移动阶段，预期不降低；首次引擎绕过 pending 复现见 sync-restart-repro.log。 |
| [TC04-30](../20260924-code-review-testcases/CR-04.md) | PASS | 原探针 10/10；本轮真实文件恢复 11/11，含最后时刻改写及重叠拒绝。 |
| [TC04-31](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-32](../20260924-code-review-testcases/CR-04.md) | PASS | sync-restart.test.cjs：DELETE/MOVE/UPDATE/CREATE 各在页面事件完成、cursor 写入前 process.exit；新进程重开 SQLite 重放，不重复下载/改写 mtime/迁移身份/写历史，最终提交游标且根 B 配置/状态/字节不变。 |
| [TC04-33](../20260924-code-review-testcases/CR-04.md) | PASS | sync-reconcile.test.cjs：真实本地哨兵文件及完整状态快照；业务错误、缺 records、null/负数/小数/页数回退、第二页失败均以对应列举错误拒绝，无清理或 cursor 写入；空目录 0 页合法但仍在根内的旧节点不被清理；恢复后第二页补齐新文件并经 syncOnce 将 cursor 从 1 推到 2。修复 null 被 Number 转成 0 的缺陷，证据 sync-reconcile-repro.log。 |
| [TC04-34](../20260924-code-review-testcases/CR-04.md) | PASS | sync-cursor.test.cjs：第一页游标写入真实 SQLite；第二页网络失败后重开数据库与引擎，从第二页游标继续并固化最终游标。 |
| [TC05-01](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：H2 真 Mapper、五线程在源流屏障并发，只有一份文件字节、count=1；待真实 MySQL 验证。 |
| [TC05-02](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：一个 URL 与四个流在 H2 单实例同时争最后一次额度，仅一个成功；待双实例 MySQL 验证。 |
| [TC05-03](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：取消/过期/关闭下载/缺 download 权限四变体前置拒绝，count=0、源对象未访问；错误提取码/验证码未覆盖。 |
| [TC05-04](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：不存在、回收站、未上传完及分享子树外节点前置拒绝，count=0、源对象未访问。 |
| [TC05-05](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开失败、URL 签名失败均抛错，count=0 且无文件响应。 |
| [TC05-06](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开后另一 URL 先授予；当前流被拒绝并关闭，响应输出流与文件长度头均未设置，count=1。 |
| [TC05-07](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：首字节前及写出部分字节后源流抛 IOException；授予 count=1 不退款、源流关闭、响应未附加 JSON。 |
| [TC05-08](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：在真实 H2 Mapper 前注入授权 UPDATE 异常，流已打开但无输出/不计次且关闭；模拟成功授权后响应丢失，客户端重试仍被额度拒绝。真实 DB 超时和网络断连未覆盖。 |
| [TC05-09](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：limit=null，连续三轮 URL+流各授予一次；每轮 count 精确增加 2，输出完整。 |
| [TC05-10](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：不支持的缩略图请求拒绝且 count=0，随后原文件流字节完整且 count=1；有效缩略图与存储失败未覆盖。 |
| [TC05-11](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：初检后源流打开期间分别取消分享、关闭下载、设为已过期；最终 H2 条件 UPDATE 均拒绝，count=0、无输出。尚缺时钟边界和口令在途语义。 |
| [TC05-12](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRateLimitedTo5MBps：未验证事务边界及全部文件场景。 |
| [TC05-13](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：limit=1 时首个预签名 URL 授予成功，应用层重试失败且 count=1；对象存储直接复用旧 URL 未演练。 |
| [TC06-01](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：两个独立 service 对象共享密钥交替消费三页游标，无重复遗漏；共用 mock ES，未运行双应用实例。 |
| [TC06-02](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：新 service 对象用同密钥消费旧对象游标；未演练应用进程重启与滚动发布。 |
| [TC06-03](../20260924-code-review-testcases/CR-06.md) | PASS | TeamSearchServiceTest：ApplicationContextRunner 启动真实搜索 Bean，缺失、空串、纯空白密钥均初始化失败；错误由 bean 初始化期抛出。 |
| [TC06-04](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：Spring 上下文分别用属性名与环境变量同名属性注入测试密钥，不含搜索 Bean 的上下文可启动；尚未用真实进程环境变量演练。 |
| [TC06-05](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：不同密钥对象拒绝旧游标，篡改签名测试通过；截断/非法编码全变体未覆盖。 |
| [TC06-06](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：更换租户、用户、查询词、页大小均拒绝且不查 ES；目录、空间及所有筛选条件未逐项覆盖。 |
| [TC06-07](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：重签过期游标被拒并返回过期码；到期边界、跨实例时钟未覆盖。 |
| [TC06-08](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest：旧游标在新密钥对象被拒；新密钥重新搜索与全实例轮换未覆盖。 |
| [TC06-09](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest 已运行合法、异密钥/篡改游标及缺密钥路径；源码检查密钥配置默认空、日志模板不含 cursorSecret，docker/.env.example 仅含占位值；未采集独立应用实例的响应与日志。 |
| [TC07-01](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：真实 jpg/jpeg/png/gif/bmp 各三种尺寸均输出可解码且尺寸受限的 JPEG；GIF 多帧首帧与完整入口未覆盖。 |
| [TC07-02](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：WebP/SVG 渲染入口预先拒绝；普通主图浏览器展示未验证。 |
| [TC07-03](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 对 B-1/B/B+1 边界检查通过；预览服务缓存写入链未覆盖。 |
| [TC07-04](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 低报及缺失长度时实际源流超限均受硬上限阻断；修复缺失长度触发 NPE 的缺陷；对象增长与缓存 PUT 链未覆盖。 |
| [TC07-05](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：真实 PNG 在像素限额 P-1 拒绝、P 通过，伪造 65536² 头在解码前受 long 乘积拦截；无 reader.read 探针和非正尺寸变体。 |
| [TC07-06](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：伪装 jpg 的非图片被拒；损坏/截断头和资源释放全变体未覆盖。 |
| [TC07-07](../20260924-code-review-testcases/CR-07.md) | PASS | ThumbnailRendererTest：C=1，闩锁占用第一个许可，第二请求在读取前拒绝，释放后第三请求成功。 |
| [TC07-08](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：读取 IOException 后流关闭、许可释放，下一请求成功；其他故障阶段未覆盖。 |
| [TC07-09](../20260924-code-review-testcases/CR-07.md) | PASS | ThumbnailRendererTest：Spring 启动对 B/P/C 的 0/负数六变体均失败；自定义低上限启动后实际生效。 |
| [TC07-10](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-11](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-12](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-13](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-14](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-15](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-16](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-01](../20260924-code-review-testcases/DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-02](../20260924-code-review-testcases/DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-03](../20260924-code-review-testcases/DB-ID.md) | PASS | full-migration-probe.ps1：完整 02～43 初始化、迁移前 compare-schema 退出 1、原 44/45、测试版 schema_version 登记、迁移后 compare-schema 退出 0。 |
| [TCDB-04](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | SchemaConsistencyTest 3 项、auth/team H2 测试及 MySQL 列对比通过；未覆盖所有大 ID 存读子场景。 |
| [TCDB-05](../20260924-code-review-testcases/DB-ID.md) | PASS | 独立 MySQL 8 临时库：原 44 成功、给 45 注入列冲突后失败且未登记版本；移除冲突重试成功，旧数据保留，重复版本登记被唯一键拒绝。证据 db-chaos.log。 |
| [TCDB-06](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | 独立 MySQL 8：大角色 9007199254740995 精确存储，回退 TINYINT 因越界失败且原值保留；旧 Integer 服务/客户端未演练。证据 db-chaos.log。 |
| [TCDB-07](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-08](../20260924-code-review-testcases/DB-ID.md) | PASS | desktop-probes.cjs：裸数字/字符串大 ID、缺失与损坏 JWT 均按预期；实际 api-client.ts。 |
| [TCDB-09](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | db-migrate.test.ts：两个根的超安全 INTEGER 字面量 root/node/cursor 迁移为逐字 TEXT，user_id 保留，二次迁移幂等；历史其他列和索引检查未全覆盖。 |
| [TCDB-10](../20260924-code-review-testcases/DB-ID.md) | PASS | sync-cursor.test.cjs：相邻大游标、真实 SQLite 持久化重开后继续分页；hasMore 不前进拒绝且不改库。 |
| [TCDB-11](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-12](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |

仍有 45 条没有独立执行证据；MySQL 故障演练不能替代多实例、Redis/S3/ES、浏览器和桌面端到端验证。
