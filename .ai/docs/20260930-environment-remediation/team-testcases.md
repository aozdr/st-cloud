# V02 真实 MySQL 服务事务用例

背景：遗留 V02 的实际服务事务隔离级别、邀请等待空间锁期间撤销/过期缺运行证据。本次只补测试，不更改生产服务与 State，不执行 Maven 或数据库操作。

输入：`TASK-20260930-environment-team-tests`、本轮 design/testcases/requirement、当前 `TeamServiceImpl` 与 `TeamRoleConcurrencyIntegrationTest`。定版设计修订为 `env-design-r1`。

分析（事实）：现有 MySQL 子类继承 8 项角色引用/删除锁顺序和 8 项权限提交/回滚用例。它们调用真实 Spring 服务和 Mapper，但没有在目标服务事务里读取连接隔离级别，也没有针对邀请等待空间锁失效的用例。未运行这些测试，不能把历史运行结果当作当前通过证据。

决策：仅在 `TeamRoleMysqlConcurrencyIntegrationTest` 增加 9 项参数化场景，总数预期为 25。池连接默认设为 `REPEATABLE_READ`，各角色写入口必须由服务代理在事务中改为 `READ_COMMITTED`；检查 Spring 活跃事务、数据源绑定、实际 JDBC 连接、MySQL 会话隔离级别。测试 URL 门禁限定独立库路径，查询参数中出现库名不能启用生产/共享库测试。

| 场景 | 新增参数 | 断言 |
| --- | --- | --- |
| 服务事务隔离 | inviteMember、updateMemberRole、createInvite、joinByCode、createRole、updateRole、deleteRole | 外层无事务；服务是 AOP 代理；空间锁检查点处活跃事务绑定实际 MySQL 连接；JDBC 为 TRANSACTION_READ_COMMITTED，`@@session.transaction_isolation` 为 READ-COMMITTED；每个入口恰检查一次。 |
| 等待时撤销 | revoke | 独立 JDBC 连接持有空间行锁；等待者完成锁前邀请读取后进入数据库确认的 LOCK WAIT；主线程真实服务撤销已提交；解锁后拒绝码 TEAM_INVITE_NOT_FOUND；无新成员。 |
| 等待时过期 | expire | 锁前永久有效；确认 LOCK WAIT 后提交一个将到达的截止时间，持锁等待真实时钟越界；解锁后拒绝码 TEAM_INVITE_EXPIRED；无新成员。 |
| 失效后的引用完整性 | revoke、expire | 失效邀请不阻止角色删除；角色不存在；无成员引用及有效邀请悬空引用。 |

已有两种顺序矩阵保留：直接邀请成员、创建邀请链接、修改成员角色、接受邀请分别先引用/先删除；接受邀请因已有有效邀请引用，两个顺序下角色删除均拒绝。已有提交/回滚权限矩阵也保留，不以新测试重复替代。

并发证据：等待者记录 `CONNECTION_ID()`；主线程查询 `information_schema.innodb_trx` 对应连接的 `trx_state='LOCK WAIT'`。只有数据库确认后才执行失效操作，最多等待 5 秒；不能只用 `Future.isDone()` 当作锁等待证据。截止时间等待也限制为 5 秒。持锁连接在 finally 回滚并关闭，工作线程清除租户/用户上下文，并在 10 秒内终止。

验证边界：本 child 完成源码检查与 URL 门禁检查，没有编译或运行测试，没有访问或写入数据库。主线程必须串行编译与真实 MySQL 运行后才能提出 TEAM01/TEST_PASS 通过。

主线程运行前：创建并初始化当前 schema 的新独立库，库名必须为 `stcloud_review_fixes_20260930` 或以该名称加下划线分段后缀；账号可查询 `information_schema.innodb_trx`（MySQL PROCESS 权限）。继承测试使用固定用户 ID，重跑整个类须使用另一个新独立库，不能清空共享库来避免冲突。密码仅放环境变量，不写入本报告或结果文件。

PowerShell 运行命令（环境变量已由主线程安全设置）：

```powershell
mvn -pl st-team -am test "-Dtest=TeamRoleMysqlConcurrencyIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest.review.mysql.url=$env:ST_TEAM_MYSQL_URL" "-Dtest.review.mysql.user=$env:ST_TEAM_MYSQL_USER" "-Dtest.review.mysql.password=$env:ST_TEAM_MYSQL_PASSWORD"
```

无 MySQL 参数时整类应跳过；跳过不构成真实 MySQL 验收通过。可单独重新执行新增 9 项，避免继承固定夹具 ID 的重复插入：

```powershell
mvn -pl st-team -am test "-Dtest=TeamRoleMysqlConcurrencyIntegrationTest#team01ActualServiceTransactionUsesReadCommitted+team01InviteInvalidatedWhileWaitingForSpaceLockCannotJoin" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest.review.mysql.url=$env:ST_TEAM_MYSQL_URL" "-Dtest.review.mysql.user=$env:ST_TEAM_MYSQL_USER" "-Dtest.review.mysql.password=$env:ST_TEAM_MYSQL_PASSWORD"
```

State Delta：仅建议 TESTCASES 的 V02 专项用例部分通过；不建议 TEST_PASS，也没有写入 State、判定 Goal 或宣称全任务完成。

风险：测试尚未编译/运行；独立库需有当前完整结构；旧 MySQL 若不支持 `@@session.transaction_isolation` 或账号缺 PROCESS 权限将明确失败。过期场景在等待期间提交截止时间，再用真实时间过期，比完全依赖启动耗时的短时邀请更可控；运行证据仍需主线程产生。

下一步：主线程串行执行上述命令，保留 Surefire XML；确认预期 25 项且非 skip；若失败，区分环境/权限缺口和产品缺陷，按本轮修订修复并重验。

变更影响：仅修改 `st-team/src/test/java/com/stcloud/team/service/TeamRoleMysqlConcurrencyIntegrationTest.java` 和本 dispatch 专属文档/结果，不修改生产代码、其他模块、历史记录、State 或数据库。
