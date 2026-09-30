# 2026-09-25 测试执行记录

代码：HEAD 912996e1b8f34904b792f29ceacab2023844e038 + 当前未提交整改；执行者：主线程。状态按 [用例总册](../testcases.md) 的完整预期判定。

总计 136 条：PASS 4，FAIL 1，PARTIAL 26，NOT RUN 105。PARTIAL 表示只完成部分场景或隔离探针，**不得算通过**。

## 核心失败

**TC04-30（P0，数据丢失）**：[sync-recovery.ts](../../../../st-desktop/src/sync/sync-recovery.ts) 在完成源文件与恢复副本的哈希核对后，调用 markWritten，再直接 unlink 源文件。探针在该回调中将源文件从 `original-v1` 写成 `user-edited-v2`；运行后源文件已消失，恢复副本仍是 `original-v1`。因此备份验证与删除之间的本地编辑会丢失。探针使用真实临时目录，全部测试文件已清理。后续需要在删除前重新证明源内容仍与副本一致，或采用能保证原子归属的保全流程。

## 已执行的套件与环境检查

- Java：首轮 `mvn -q test -DskipITs` 中，st-common/auth/core/share/team/sync/search/preview/admin 的当日 Surefire 报告共 481 项、0 失败、0 错误、0 跳过，其中 SchemaConsistencyTest 3 项通过。全命令退出码为 1，因为 st-api.ReindexIntegrationTest 完整上下文缺少搜索游标密钥。
- st-api 不能计通过：加测试专用密钥的重跑在到达 st-api 前主动停止，因为该测试会调用真实 `reindexAll`，当前没有隔离数据库和 Elasticsearch；没有执行该全量重建。
- 桌面 `npm test` 和 `npm run test:round3`：各 19 项通过，属于重叠套件，不相加为 38 项。`npx tsc --noEmit` 退出码 0。
- Web `npm run build` 退出码 0；`node src/lib/hash-contract.test.mjs` 6 项通过。
- 现有开发库只读检查：`compare-schema.ps1` 退出码 0，19 个 H2 表的共有列对齐，迁移清单无未登记 SQL；列查询确认两列 role 为 BIGINT NOT NULL DEFAULT 2，security_version 为 BIGINT NOT NULL DEFAULT 0，schema_version 记录 20260924.1 与 44/45 SQL。未向现有开发库写入。
- 隔离迁移：[migration-probe.ps1](migration-probe.ps1) 在本轮临时 MySQL 8.0.46 容器（仅 127.0.0.1:13306/stcloud_test）对合成旧结构实际执行原 44/45 SQL。旧值与异常引用、大 ID、默认值、备注、存量及新用户安全版本通过。两个临时容器与过渡 SQL 副本已清理，现有 stcloud 库未被写入。
- 完整升级：[full-migration-probe.ps1](full-migration-probe.ps1) 在另一个隔离容器的内部 stcloud 库跑通 02～43 初始化。迁移前结构对比退出 1（缺 security_version 与待登记 SQL）；执行原 44/45 并登记仅用于隔离测试的 20260925.1 后，结构对比退出 0。初次尝试误用内部库名 stcloud_test，因历史 SQL 固定 USE stcloud 而失败；调整隔离容器内部库名后成功。所有临时容器与过渡 SQL 副本已清理，宿主现有 3306 库未写入。
- [桌面专项探针](desktop-probes.cjs)：10 个场景中 9 个探针断言通过，TC04-30 失败。前九个以当前 TypeScript 源码配合内存 FS/API/DB 执行；它们只覆盖各编号的一部分，见下表。

## 全部用例状态

| 编号 | 状态 | 本轮证据或未执行原因 |
|---|---|---|
| [TC01-01](../CR-01.md) | PARTIAL | AuthServiceIntegrationTest.login_disabledUser_rejected：仅覆盖禁用登录，未覆盖提交后旧令牌撤权。 |
| [TC01-02](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-03](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-04](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-05](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-06](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-07](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-08](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-09](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-10](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-11](../CR-01.md) | PARTIAL | AuthServiceIntegrationTest.refreshToken_rotatesWhenStored：仅单请求轮换，未覆盖 Redis CAS 并发。 |
| [TC01-12](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-13](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-14](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-15](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-16](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-17](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-18](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-19](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-20](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-21](../CR-01.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC01-22](../CR-01.md) | PARTIAL | AuthServiceIntegrationTest.register_createsUserAssignsDefaultRoleAndIssuesValidToken：仅成功注册。 |
| [TC02-01](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-02](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-03](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-04](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-05](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-06](../CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：仅禁用角色变体。 |
| [TC02-07](../CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.customRolePermissionsApplied：验证自定义 view+upload，未覆盖 view-only 拒绝。 |
| [TC02-08](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-09](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-10](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-11](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-12](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-13](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-14](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-15](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-16](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-17](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-18](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-19](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-20](../CR-02.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC02-21](../CR-02.md) | PARTIAL | TeamServicePermissionIntegrationTest.disabledCustomRoleDeniesPermissions：未遍历各授权入口。 |
| [TC03-01](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-02](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-03](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-04](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-05](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-06](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-07](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-08](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC03-09](../CR-03.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-01](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-02](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-03](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-04](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-05](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-06](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-07](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-08](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-09](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-10](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-11](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-12](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-13](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-14](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-15](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-16](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-17](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-18](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-19](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-20](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-21](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-22](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-23](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-24](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-25](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-26](../CR-04.md) | PARTIAL | desktop-probes.cjs 的当前源码+内存替身通过；未覆盖该编号要求的全部参数/跨服务或进程重启变体。 |
| [TC04-27](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-28](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-29](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-30](../CR-04.md) | FAIL | desktop-probes.cjs：真实临时文件 V1 校验后改为 V2，源被删除，恢复副本仅有 V1。 |
| [TC04-31](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-32](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-33](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC04-34](../CR-04.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-01](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-02](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-03](../CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.allowDownloadZeroStreamRejected：仅下载开关变体。 |
| [TC05-04](../CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRejectsSamePrefixSibling：仅同名前缀越界变体。 |
| [TC05-05](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-06](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-07](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-08](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-09](../CR-05.md) | PARTIAL | ShareServiceImplNoTransactionIntegrationTest.getDownloadUrl_doesNotOpenTransaction：不限额 URL 两次计数，未覆盖并发和流。 |
| [TC05-10](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-11](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC05-12](../CR-05.md) | PARTIAL | ShareServiceImplSecurityIntegrationTest.streamShareFileRateLimitedTo5MBps：未验证事务边界及全部文件场景。 |
| [TC05-13](../CR-05.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-01](../CR-06.md) | PARTIAL | TeamSearchServiceTest.consecutiveCursorPagesDoNotRepeatVisibleRecords：仅单实例分页。 |
| [TC06-02](../CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-03](../CR-06.md) | PARTIAL | TeamSearchServiceTest.missingCursorSecretFailsFast 与 st-api 缺配置启动失败：未验证纯空白变体。 |
| [TC06-04](../CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-05](../CR-06.md) | PARTIAL | TeamSearchServiceTest.tamperedCursorIsRejectedAndEsIsNotCalled：未覆盖不同密钥和所有非法编码。 |
| [TC06-06](../CR-06.md) | PARTIAL | TeamSearchServiceTest.cursorIsBoundToPrincipalAndAllQueryConditions：测试数个绑定维度，未覆盖全部组合。 |
| [TC06-07](../CR-06.md) | PARTIAL | TeamSearchServiceTest.expiredCursorIsRejectedBeforeEs：未覆盖到期临界点与跨实例。 |
| [TC06-08](../CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC06-09](../CR-06.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-01](../CR-07.md) | PARTIAL | PreviewServiceIntegrationTest.previewImage_generatesThumbnailAndReturnsImageUrl：仅一个受支持格式/尺寸。 |
| [TC07-02](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-03](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-04](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-05](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-06](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-07](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-08](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-09](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-10](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-11](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-12](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-13](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-14](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-15](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TC07-16](../CR-07.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-01](../DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-02](../DB-ID.md) | PASS | migration-probe.ps1：独立临时 MySQL 8.0.46 使用原 44/45 SQL；旧行、异常角色、备注、默认值、大 ID、存量/新增安全版本均通过。 |
| [TCDB-03](../DB-ID.md) | PASS | full-migration-probe.ps1：完整 02～43 初始化、迁移前 compare-schema 退出 1、原 44/45、测试版 schema_version 登记、迁移后 compare-schema 退出 0。 |
| [TCDB-04](../DB-ID.md) | PARTIAL | SchemaConsistencyTest 3 项、auth/team H2 测试及 MySQL 列对比通过；未覆盖所有大 ID 存读子场景。 |
| [TCDB-05](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-06](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-07](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-08](../DB-ID.md) | PASS | desktop-probes.cjs：裸数字/字符串大 ID、缺失与损坏 JWT 均按预期；实际 api-client.ts。 |
| [TCDB-09](../DB-ID.md) | PARTIAL | st-desktop db-migrate.test.ts：大 ID 存读和二次迁移通过；未覆盖该用例列出的所有 ID/cursor 列。 |
| [TCDB-10](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-11](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |
| [TCDB-12](../DB-ID.md) | NOT RUN | 本轮没有覆盖该用例全部前置数据、故障注入或断言的独立执行证据。 |

## 执行边界

未运行的用例需要合成租户/团队/文件夹具、可控制的事务屏障、隔离的 Redis/MySQL/S3/Elasticsearch 或浏览器端到端环境。当前可访问的本机服务连着开发数据，不能把破坏性迁移、重建索引、真实删除/并发写入当成隔离测试执行。既有单元/集成套件通过不代表这 136 条均通过。
