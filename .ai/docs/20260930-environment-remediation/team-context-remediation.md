# 团队后台与异步租户整改

## 背景
独立审查发现 TeamActivityHelper 在异步线程查询用户和写活动时没有传递租户；ExternalMemberExpireTask 与 FileLockExpireTask 只依赖默认租户，并按旧扫描 ID 无条件删除或释放。

## 输入
DISPATCH-env-team-context-01、TASK-20260930-environment-team-context、env-design-r1。只修改三类及新测试，不修改 TeamServiceImpl、共享 State、迁移或历史产物，不运行 Maven、Git 或真实调度。

## 分析
事实：活动日志缺少显式 tenantId；旧外部过期清理 deleteById；旧文件锁清理只有 ID 条件。扫描与写入之间发生延期、类型转换或重新锁定时，旧快照可能清理当前有效状态。核心 TenantTaskRunner 已存在，按启用租户执行并精确恢复线程上下文。

## 决策
活动日志在调用线程解析租户并保留真实缺失诊断，捕获原始模式。工作线程设置相同 ID/模式，用户查询与活动写入在该作用域内进行，活动显式 tenantId；finally 恢复工作线程原始 ID/模式。

两类定时任务调用 TenantTaskRunner。外部成员每成员使用 READ_COMMITTED 的 TransactionTemplate，先锁空间，再按 ID、spaceId、memberType=1、扫描 expireAt 与当前到期条件执行逻辑删除。文件锁清理按 ID、lockedBy、lockedAt、扫描 lockExpireAt 与当前到期条件原子更新，统计实际成功行数。

## State Delta（proposal）
仅建议本子任务 IMPLEMENTED/env-code-r2 通过，整体 IMPLEMENTED 和测试门禁由主线程裁决。未写 State。

## 验证证据
新增 TeamActivityTenantTest 四项：两个租户复用同一工作线程且用户查询/活动写入隔离；查询失败后清理；已有工作线程 ID/模式精确恢复；无请求租户仍发出原诊断。

新增 TeamExpirationTaskIntegrationTest 四项：真实 H2 Mapper 上两租户过期删除并验证空间先于成员写；扫描后延期/转内部成员保留；两租户过期解锁并恢复调用者；扫描后延期/重锁保留。

已完成源码/调用者静态检查。依据 Envelope 不运行 Maven，因此上述测试尚未执行，本报告不声称测试通过或真实 MySQL 并发验证完成。主线程串行执行两类及整体受影响回归。

## 风险
H2 用例用于验证真实 SQL 条件，不代替 MySQL 锁等待证据。单成员短事务避免整批长期占锁；单租户扫描失败由既有 Runner 隔离。没有新增网络调用或权限规则。

## 下一步
主线程串行 Maven 编译和运行 TeamActivityTenantTest、TeamExpirationTaskIntegrationTest；并与 TeamService 撤权锁序、真实 MySQL 回收并发证据合并复审。

## 变更影响
无 API/数据库结构变化。正常请求诊断减少，真实漏设保留；启用租户后台任务获得一致的扫描范围；已延期、转换或重锁的数据保留。保留用户现有改动。
