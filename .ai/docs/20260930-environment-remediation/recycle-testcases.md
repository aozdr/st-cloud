# V06 真实 MySQL 并发专项

背景：遗留团队回收站缺撤权与恢复/永久删除交错、恢复与永久删除竞争的真实数据库证据。本 child 只补测试，未运行 Maven、未访问或写数据库、未修改产品源码或 State。

输入：本轮 TASK、design/testcases、当前 RecycleBinServiceImpl、TeamStorageMapper、TeamRecycleBinIntegrationTest，以及当前 Dispatch 协议。设计修订为 env-design-r1。

分析（初始源码事实）：原回收服务先普通读取节点并查询管理员关系，再 UPDATE/DELETE；权限查询无行锁。永久删除读取回收态后可能等待删除锁。主线程现状运行日志 recycle-mysql-before.log 记录 4 项执行、4 项 failure、0 error、0 skip。其中撤权先提交的恢复/永久删除两项，以及恢复先行后永久删除一项，分别得到不应成功的 completed，构成 3 项真实产品窗口复现。删除先行后恢复一项在 Outbox 计数处失败：测试错误按 event_log.tenant_id 过滤，但该表为系统级，可信租户位于 payload.fileNode.tenantId；该项属于测试统计缺陷，不能当作第四个产品缺陷。

决策：新增 TeamRecycleMysqlConcurrencyIntegrationTest，使用真实 MySQL、Spring 服务事务代理、MyBatis Mapper、引用计数、配额以及 ReliableEventPublisher 写入 event_log。测试不套外层事务；池基线 RR，插件检查实际服务交给 MyBatis 的连接为 RC，并记录该连接的 CONNECTION_ID。

| 场景 | 可控交错 | 预期 |
| --- | --- | --- |
| 撤权先提交 × restore/delete | 控制连接依照 team_space→file_node 锁顺序持锁；目标服务进入真实数据库锁等待后，将管理员 role 改为 2 并提交 | 等锁后权限拒绝；节点仍回收；团队配额 32、个人配额 100、对象引用 1 不变；没有 Outbox 或直接 S3 删除 |
| restore 先获锁，delete 后获锁 | 第一请求已在控制连接上等待，才排入第二请求；数据库分别确认等待后释放控制连接 | 恢复成功；后到删除拒绝正常态；节点正常、配额/引用不变；仅 INDEX 与 CREATE Outbox |
| delete 先获锁，restore 后获锁 | 同上，交换服务先后顺序 | 删除成功；后到恢复拒绝已删除节点；团队配额退至 0、个人配额 100、对象引用 0；仅 FILE_INDEX/DELETE 与 PHYSICAL_DELETE Outbox，不产生 CREATE |

实际等待依据 performance_schema.data_lock_waits，关联 requesting/blocking threads 的 PROCESSLIST_ID；不仅检查 Future 是否结束。按同一控制连接分别确认第一、第二请求的等待，避免仅靠启动线程时间猜顺序。

边界：角色撤销由独立 JDBC 事务更新 role；空间锁遵循现有 TeamService.updateMemberRole 的顺序，但不是调用 st-team API，因为本测试位于 st-core，不能反向依赖 st-team。真实 Outbox 写入保留，投递 ApplicationEventPublisher 受控且不运行物理删除消费者；因此不证明真实 RocketMQ/S3 消费链路，也不证明浏览器或 Electron。

隔离：URL 必须完整匹配独立库 stcloud_team_recycle_20260930_env；普通共享库、生产库查询参数伪装、相似后缀和 H2 不会启用测试。夹具用时间戳和原子序列生成唯一 ID，可重复运行并保留审计行，不清空库、不删除历史夹具。密码使用环境变量 TEST_TEAM_RECYCLE_MYSQL_PASSWORD，不在命令参数、日志或文档中写值。

主线程运行命令（已安全设置密码环境变量）：

```powershell
mvn -o -pl st-core -am test '-Dtest=TeamRecycleMysqlConcurrencyIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.team.recycle.mysql.url=jdbc:mysql://127.0.0.1:3306/stcloud_team_recycle_20260930_env'
```

主线程须保留 Surefire XML 并确认预期 4 项非 skip；现状日志已存在。主线程已修正共享空间/节点锁及锁后权限/状态重读，并将 Outbox 统计改为 payload.fileNode.tenantId；child 只读核对当前源码，未取得最终运行日志。当前构建中的 Java 不由 child 并行修改，完整重跑前不提出 TEST_PASS。

风险：child 未执行 Maven，运行由主线程串行完成；尚未取得修正 Outbox 统计后的全绿证据。账号需读取 MySQL 8 的 performance_schema.data_lock_waits/threads；库结构必须为当前版本。控制连接关闭自动释放锁，工作线程 finally 清理上下文，执行器关闭并断言终止。队列先后依据真实锁等待次序；若 MySQL 调度未保持该次序，须由主线程结合锁/事件证据区分测试调度问题和产品问题。初版 purge/admin 的反向锁序曾由 child 报告，主线程现已统一所有入口调用 lockRecycleNode（space→node），仅静态核对，不宣称这些入口死锁专项已通过。

State Delta：仅 TESTCASES 中 V06 部分用例 proposal；无 State 写入或 Goal/ACCEPT 判定。

下一步：主线程串行运行并收集复现/修复后证据；实际缺陷由主线程在产品源码 scope 内处理。其余已有 H2/MySQL 18 项矩阵继续保留并需重新运行。

变更影响：新增一个 Java 测试、本独立文档与结果；未改其他 child 文件、数据库迁移、生产代码、历史记录或 State；未执行 Git/Maven/网络/数据库操作。
