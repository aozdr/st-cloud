# 2026-09-25 续测逐项结果

基于[原执行记录](../20260924-code-review-testcases/execution-20260925/results.md)和[上轮失败修复](../20260925-test-fix/results.md)。共 136 条：PASS 11，FAIL 0，PARTIAL 33，NOT RUN 92。PARTIAL 不算通过。

本轮发现并修复了数字游标被 String() 接受导致潜在精度丢失的问题；服务端 Jackson 将 Long 输出为字符串。合法字符串大游标仍保留原值。

## 本轮验证

- 全量 `mvn -q test -DskipITs`：10 模块 495 项，0 失败、0 错误、0 跳过；[模块明细](java-counts.json)与[原始日志](maven-full.log)。
- Maven 定向 `ThumbnailRendererTest`：8/8，零失败、零错误、零跳过；实际 ImageIO、受控流和 Spring 上下文。
- Maven 定向 `ShareServiceImplNoTransactionIntegrationTest`：5/5；五流及 URL+四流竞争均只授予一次，源流/签名失败与额度抢尽的副作用也已验证。
- 桌面 `npm test`：既有 19 项 + 同步专项 17 项通过；`npx tsc --noEmit` 与 `npm run build:main` 退出码均为 0。
- 测试夹具首次重复注册 S3 Bean 导致定向测试失败；移除重复注册后通过。该失败由测试配置引起，生产代码未因此修改。
- 分享定向测试首次因既有固定分享码在两个非事务用例中重复而失败；改为按文件 ID 唯一后通过。
- [缩略图测试](../../st-core/src/test/java/com/stcloud/core/service/ThumbnailRendererTest.java)和[游标测试](../../st-desktop/src/sync/sync-cursor.test.cjs)均接入仓库测试路径。

## 全部用例

| 编号 | 状态 | 证据或缺口 |
|---|---|---|
| [TC01-01](../20260924-code-review-testcases/CR-01.md) | PARTIAL | AuthServiceIntegrationTest.login_disabledUser_rejected：仅覆盖禁用登录，未覆盖提交后旧令牌撤权。 |
| [TC01-02](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-03](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-04](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-05](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-06](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-07](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-08](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-09](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-10](../20260924-code-review-testcases/CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
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
| [TC02-03](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-04](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-05](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-06](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：仅禁用角色变体。 |
| [TC02-07](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.customRolePermissionsApplied：验证自定义 view+upload，未覆盖 view-only 拒绝。 |
| [TC02-08](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-09](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-10](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-11](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-12](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-13](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-14](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-15](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-16](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-17](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-18](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-19](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-20](../20260924-code-review-testcases/CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-21](../20260924-code-review-testcases/CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：未遍历各授权入口。 |
| [TC03-01](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-02](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-03](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-04](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-05](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-06](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-07](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-08](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-09](../20260924-code-review-testcases/CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-01](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-02](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-03](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-04](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-05](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-06](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-07](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-08](../20260924-code-review-testcases/CR-04.md) | PASS | sync-cursor.test.cjs：数字/非法游标、损坏 changes/hasMore、缺失/错误 v2、hasMore 不前进均拒绝且不固化游标。 |
| [TC04-09](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-10](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-11](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-12](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-13](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-14](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-15](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-16](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-17](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-18](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-19](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-20](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-21](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-22](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-23](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-24](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-25](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-26](../20260924-code-review-testcases/CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-27](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-28](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-29](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-30](../20260924-code-review-testcases/CR-04.md) | PASS | 原探针 10/10；本轮真实文件恢复 11/11，含最后时刻改写及重叠拒绝。 |
| [TC04-31](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-32](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-33](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-34](../20260924-code-review-testcases/CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-01](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：H2 真 Mapper、五线程在源流屏障并发，只有一份文件字节、count=1；待真实 MySQL 验证。 |
| [TC05-02](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest：一个 URL 与四个流在 H2 单实例同时争最后一次额度，仅一个成功；待双实例 MySQL 验证。 |
| [TC05-03](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.allowDownloadZeroStreamRejected：仅下载开关变体。 |
| [TC05-04](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRejectsSamePrefixSibling：仅同名前缀越界变体。 |
| [TC05-05](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开失败、URL 签名失败均抛错，count=0 且无文件响应。 |
| [TC05-06](../20260924-code-review-testcases/CR-05.md) | PASS | ShareServiceImplNoTransactionIntegrationTest：源流打开后另一 URL 先授予；当前流被拒绝并关闭，响应输出流与文件长度头均未设置，count=1。 |
| [TC05-07](../20260924-code-review-testcases/CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-08](../20260924-code-review-testcases/CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-09](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest.getDownloadUrl_doesNotOpenTransaction：不限额 URL 两次计数，未覆盖并发和流。 |
| [TC05-10](../20260924-code-review-testcases/CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-11](../20260924-code-review-testcases/CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-12](../20260924-code-review-testcases/CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRateLimitedTo5MBps：未验证事务边界及全部文件场景。 |
| [TC05-13](../20260924-code-review-testcases/CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-01](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest.consecutiveCursorPagesDoNotRepeatVisibleRecords：仅单实例分页。 |
| [TC06-02](../20260924-code-review-testcases/CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-03](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest.missingCursorSecretFailsFast 与 st-api 缺配置启动失败：未验证纯空白变体。 |
| [TC06-04](../20260924-code-review-testcases/CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-05](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest.tamperedCursorIsRejectedAndEsIsNotCalled：未覆盖不同密钥和所有非法编码。 |
| [TC06-06](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest.cursorIsBoundToPrincipalAndAllQueryConditions：测试数个绑定维度，未覆盖全部组合。 |
| [TC06-07](../20260924-code-review-testcases/CR-06.md) | PARTIAL | TeamSearchServiceTest.expiredCursorIsRejectedBeforeEs：未覆盖到期临界点与跨实例。 |
| [TC06-08](../20260924-code-review-testcases/CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-09](../20260924-code-review-testcases/CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-01](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：真实 PNG 转可解码 JPEG 且尺寸正确；尚未覆盖所有格式和尺寸入口。 |
| [TC07-02](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：WebP/SVG 渲染入口预先拒绝；普通主图浏览器展示未验证。 |
| [TC07-03](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 对 B-1/B/B+1 边界检查通过；预览服务缓存写入链未覆盖。 |
| [TC07-04](../20260924-code-review-testcases/CR-07.md) | PARTIAL | ThumbnailRendererTest：HEAD 小、实际流超限停止且关闭；缺长度与对象增长变体未覆盖。 |
| [TC07-05](../20260924-code-review-testcases/CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
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
| [TCDB-05](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-06](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-07](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-08](../20260924-code-review-testcases/DB-ID.md) | PASS | desktop-probes.cjs：裸数字/字符串大 ID、缺失与损坏 JWT 均按预期；实际 api-client.ts。 |
| [TCDB-09](../20260924-code-review-testcases/DB-ID.md) | PARTIAL | st-desktop db-migrate.test.ts：大 ID 存读和二次迁移通过；未覆盖该用例列出的所有 ID/cursor 列。 |
| [TCDB-10](../20260924-code-review-testcases/DB-ID.md) | PASS | sync-cursor.test.cjs：相邻大游标、真实 SQLite 持久化重开后继续分页；hasMore 不前进拒绝且不改库。 |
| [TCDB-11](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-12](../20260924-code-review-testcases/DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |

本轮仍缺少隔离的多实例、Redis/MySQL/S3/Elasticsearch 和浏览器端到端夹具。未运行的 92 条不代表失败或通过。
