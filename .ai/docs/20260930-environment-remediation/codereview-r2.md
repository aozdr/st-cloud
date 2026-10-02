# 独立代码复审：env-code-r2

## 背景

执行者 `/root/code_review_r2`，Dispatch `DISPATCH-env-code-review-02`。结论：当前 CODE_REVIEW 建议 pass，首轮 CR01–CR03 已关闭；本轮范围内没有发现需阻断的未修复代码缺陷。此结论只针对代码审查，不关闭 TEST_PASS、运行版本检查或整体 ACCEPT。

## 输入

已核对当前 Dispatch schema/protocol、TASK、design、testcases、changereport、requirement、首轮 codereview.md 及最小 State。State 的代码修订为 env-code-r2，skillRefs 为 `-`。遗留文档仅用于追溯。只读核查当前源码、迁移、配置、测试及本轮证据；没有运行 Maven、Git、数据库写入或修改产品/State。

## 分析

### 首轮问题关闭依据

| 项目 | 当前源码事实与验证证据 | 决定 |
| --- | --- | --- |
| CR01 失效成员写路径未共享空间锁 | TeamServiceImpl 的 removeMember:307–319、leaveSpace:458–480、setExternalMember:1041–1050、setExternalConfig:1065–1072 均使用 READ_COMMITTED，在权限/成员/配置读取前调用 lockRoleWrites。TeamStorageMapper 的回收空间锁取得同一 team_space 行；回收写入先空间后节点，锁后重读权限/状态。TeamRoleMysqlConcurrencyIntegrationTest 在真实服务事务绑定连接断言 RC，并以 performance_schema 精确观察四个失效入口等待真实空间行锁；backend-full-r2.log 中该类 33 项零失败/错误/跳过。 | 关闭 |
| CR02 团队活动异步租户遗漏 | TeamActivityHelper:50–61 在调用线程解析 tenantId/mode，worker 使用同一作用域查询用户、显式设置 activity.tenantId；:80–83 finally 精确恢复原始 ID/mode。TeamActivityTenantTest 四项覆盖两个非默认租户、异常、已有 worker 上下文和真实漏设诊断；当前日志四项通过。 | 关闭 |
| CR03 过期任务默认租户及旧快照写入 | 两任务通过 TenantTaskRunner 逐启用租户扫描。ExternalMemberExpireTask:45–58 每成员短事务 RC，先锁空间，再以 ID/spaceId/memberType/扫描 expireAt/当前到期条件删除；FileLockExpireTask:43–53 使用 lockedBy/lockedAt/lockExpireAt 与当前到期条件 CAS。TeamExpirationTaskIntegrationTest 四项真实 H2 Mapper 覆盖两租户、延期/转内部成员、延期/重锁保留及恢复上下文，日志四项通过。锁序与当前 SQL 已核查；该 H2 证据不冒充 MySQL 调度并发实测。 | 关闭 |

### 新增修复核查

- **MQ 租户。** SyncChangeMessageConsumer 在 alreadyProcessed 查询前建立事件节点租户作用域，插入与幂等早退/异常都在 finally 中恢复原始上下文；SyncChangeLogListener 对异步插入采取相同作用域。缺失历史租户仍保留原诊断。SyncTenantScopeTest 的重新设置桩采用 doReturn，避免执行先前 thenThrow 桩；三项实际结果见 backend-r2-sync-finish.log。没有把 mock 上下文证据扩大为真实多租户 MQ 全链路。
- **分页与重复根映射。** sync-reconcile.ts:119–125 严格接受安全非负整数或纯十进制字符串 pages，拒绝空串/null/小数/负数/不完整响应；:133–134 规范化前缀，:28–42 将同步根纳入 seen，只清除同节点且同绝对文件的斜杠别名。不会通过该修复移动字节或覆盖另一个节点的当前映射。
- **重复原件保全。** :66–78 将路径、持久同步状态和基线游标纳入稳定 SHA256 操作 ID，同轮失败重试稳定，同节点后续移动/修改另建恢复轮次；只对原路径匹配的旧 legacy 清单恢复兼容。sync-recovery.ts 保留原子 rename、先 pending 后 complete、越界/链接检查和目标已存在时保留两份的规则。受影响 34 项零失败见 desktop-recovery-rounds-pass.log。live-report 与真实链路日志记录同一污染 SQLite 连续两轮通过，恢复清单和旧字节保留；相关桌面源哈希与本次读取一致。
- **Web/Desktop 构建。** Vite 普通 Web base 为 `/`；desktop mode 为 `./`，桌面 build:web 明确传 `--mode desktop`，package.ps1 的相对资源检查继续适用。BrowserRouter 的深层刷新因原相对 base 请求错误 JS/CSS 的触发已被修复；独立原生 Vite preview 复验报告清楚记录正确 MIME/HTTP200。没有改变 HTTP/API 或路由契约。

### 其它热点与证据边界

认证主进程/页面仍以会话、服务器和代次隔离 singleflight；完整令牌对更新后通知/持久化，迟到 401 重用新 access，临时网络/5xx 保留凭据，明确认证拒绝清理。IPC 旧两参数调用兼容。live 专项为真实后端/Redis和新 Node 进程恢复；宿主适配及临时磁盘不替代 Electron 原生窗口/IPC 验收。

独立 UUID 候选的 READY→ADOPTED 与 GC 条件认领互斥，失败/去重败者延迟回收，外部 PUT/DELETE 仍在数据库事务外；引用检查含回收节点与历史版本，未知迟到 PUT 保留墓碑。去重当前读去掉冗余 LIMIT 后保留唯一键单行语义。抽查合并的四条外部写入、引用/配额和提交后 Outbox 边界，没有发现本次整合引入的直接误删已采用对象路径。

42/47 索引检查存在性，46 缺列和已有值分支不重置安全版本。schema-after 与独立 46 首次/重复比较证据通过；compare-schema 的结论仅覆盖其共享表列集合和迁移登记，不能描述为全库类型/索引完全比较。

分享胶卷错误绑定 src 请求身份，陈旧 handler 不能写当前身份；独立 Chrome 17 项与 4.508:1/15.862:1 对比度证据维持有效。故意损坏/503 图片是故障注入；本轮子线程空白页面由 JS/CSS 路径导致。s3-current-20261001.log 记录真实 Docker S3 签名 HEAD、随机 PUT/GET 与 SHA256 字节校验通过；没有将该探针扩大为图片转换/缩略图全部验收。

Docker 配置固定实装 digest、补业务探针；旧 Broker store 无持久卷和 ES 本地镜像需先构建的限制均有明确记录。当前未重建容器，历史 137/OOM 原因没有被伪称已定位。相关产品源扫描没有发现遗留合并标记；本 child 未执行 Git，index 状态由主线程独立核查负责。

## 决策

首轮三项关闭，当前代码复审建议 pass。修复前全量 backend-full-r2.log 的 TeamCopyDatabaseIntegrationTest 14 Errors 明确来自测试上下文缺候选 service bean；生产实现已有 @Service，当前测试配置已补 mock bean。主线程仍须用最终批次证明完整测试/打包通过，本审查没有将修复前 BUILD FAILURE 改写为成功，也没有代替测试门禁。

## State Delta（仅 proposal）

CODE_REVIEW=pass；by=/root/code_review_r2；dispatchId=DISPATCH-env-code-review-02；validatedRevision=env-code-r2；evidenceRef=.ai/runtime/results/DISPATCH-env-code-review-02.json。没有写 State 或执行 Evaluate。

## 风险

只审查需求相关热点，不代表全仓安全审计。实际部署 r2、完整测试、Electron 实机和长期压力由对应门禁/外部验证负责；live 后端证据仍使用 r1 不可变 JAR，不能证明尚未部署的 r2 MQ 告警消失。后续产品源码修改不能自动继承此结果。

## 下一步

主线程核对最终源码哈希和本 attempt 产物后 Evaluate；串行完成最终构建、当前 JAR 部署及受影响真实运行检查，随后由独立 tester 汇总 TEST_PASS。已指出的常规测试上下文修复无需新增范围裁决。

## 变更影响

本 child 只新增自己的 codereview-r2.md 和独立 JSON 结果，首轮报告保留；没有产品、配置、测试、Git、历史产物或 State 写入，也没有子派发。
