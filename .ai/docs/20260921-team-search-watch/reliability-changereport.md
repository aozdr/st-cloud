# 文件关注可靠性测试变更报告

## 背景

本次续做对应 `TASK-20260921-team-search-watch-reliability` 和
`DISPATCH-20260921-TSW-RELIABILITY-A2`。上次额度中断后，H2 可靠性集成测试已落盘，当前编译现场指出两个直接调用 `ObjectMapper.readTree` 的测试方法未处理 `JsonProcessingException`。

## 输入

- `.ai/tasks/TASK-20260921-team-search-watch-reliability.md`
- `.ai/docs/20260921-team-search-watch/design.md`
- `.ai/docs/20260921-team-search-watch/testcases.md`
- `st-team/src/test/java/com/stcloud/team/service/FileWatchReliabilityIntegrationTest.java`
- 当前工作树中已有的关注捕获、投递、核权和 H2 schema 实现（只读核对）

## 分析

事实：`cancelledWatch_thenResubscribe_doesNotConsumeOldDeliveryGeneration` 和
`deletedDirectory_matchesParentAndDescendantWatches_oncePerUser` 直接调用了受检异常签名的 `readTree`，但方法签名未声明异常；同类的重叠订阅测试已经声明 `throws Exception`。

事实：可靠性夹具中的每个投递用户均通过基类 `insertUser` 写入 H2 `sys_user`。为兼容 watchAgent 对事件操作者有效性的检查，又补充了回滚场景的 `41013` 以及边界场景的 `44013`、`44014` 正常用户记录；不会改变订阅用户、租户或权限断言。

## 决策

- 为上述两个测试方法补充 `throws Exception`，让 `JsonProcessingException` 由 JUnit 测试入口处理。
- 保留真实 Spring `TransactionTemplate`、MyBatis-Plus Mapper 和 H2 fixture；未把捕获、投递或 Mapper 替换为全量 mock。
- 只修改白名单测试文件，并新增本 dispatch 要求的变更报告和结果记录；未修改生产代码、State 或其他模块。

## State Delta（proposal）

仅向主线程提议：在 `IMPLEMENTED` criterion 下登记本 dispatch 的测试编译修复和 H2 用户夹具补全，证据为本报告及结果 JSON。未写入 `.ai/state/20260921-team-search-watch.yaml`。

## 风险

- 按 dispatch 约束未运行 Maven，因此尚未证明 `testCompile`、H2 集成测试或并发测试在主线程实际环境通过。
- 生产用户有效性检查由 watchAgent 负责；若其接口或字段契约继续变化，主线程串行测试可能暴露新的适配问题。
- 数据库 schema 对比、迁移和全量可靠性验收不在本 dispatch 内执行。

## 下一步

主线程读取结果后，串行执行 `st-team` 相关 testCompile/H2 测试，并根据真实退出码评估 `IMPLEMENTED` criterion；若生产用户校验改变依赖，再回到白名单测试夹具适配。

## 变更影响

仅影响 `FileWatchReliabilityIntegrationTest` 的 checked-exception 编译适配和 H2 正常用户夹具；可靠性测试覆盖的事务回滚、订阅代际、租户/操作者边界、目录删除、通知失败重试、并发幂等及团队成员失权断言保持不变。

## A3 续做：watch-tests-r2 两处失败

### 背景

本次续做对应 `DISPATCH-20260921-TSW-RELIABILITY-A3`。主线程提供的
`watch-tests-r2.log` 显示可靠性集成测试仍有两处失败：
`actorSelf_isSkipped_crossTenantWatch_isIgnored_andRevokedOwnerIsSuppressed`
在投递前抛出租户上下文不一致，`teamMemberPermission_isRecheckedAtDeliveryTime`
在撤权后得到状态 3（已发送）而非状态 4（已抑制）。

### 输入

- `.ai/tasks/TASK-20260921-team-search-watch-reliability.md`
- `.ai/docs/20260921-team-search-watch/design.md`
- `.ai/docs/20260921-team-search-watch/testcases.md`
- `.ai/docs/20260921-team-search-watch/watch-tests-r2.log`
- `st-team/src/test/java/com/stcloud/team/service/FileWatchReliabilityIntegrationTest.java`
- `st-team/src/main/java/com/stcloud/team/service/FileWatchDeliveryProcessor.java`（只读）
- `st-team/src/main/java/com/stcloud/team/service/TeamFileAccessPolicyImpl.java`（只读）

### 分析

事实：边界测试先调用 `deliveries(tenantId, ...)`，再调用
`deliveries(otherTenantId, ...)` 验证异租户隔离。该测试 helper 会设置
`TenantContext`，因此第二次调用后当前线程仍是异租户；随后直接调用
`processOne(tenantId, ...)`，命中生产处理器对显式租户和 ThreadLocal 租户一致性的保护。
这是测试夹具上下文未恢复，不是权限断言应被放宽。

事实：团队成员撤权路径使用 `member.setDeleted(1); updateById(member)`，但没有检查受影响行或
从真实 Mapper 验证逻辑删除，r2 日志显示处理器仍按有效成员完成投递（状态 3）。
`TeamMember` 继承 `BaseEntity` 的 `@TableLogic`，测试应走真实 `deleteById`，再确认
`selectById` 已不可见，才能证明送达时成员核权会观察到失权。

### 决策

- 在边界测试的异租户查询后恢复 `setTenant(tenantId)`；使用真实 `FileNodeMapper` 更新并断言影响行数为 1、当前 owner 已更新，再执行投递断言。
- 在团队撤权测试中使用真实 `TeamMemberMapper.deleteById` 触发逻辑删除，断言影响行数为 1 且 `selectById` 返回空，再保持状态 4 和无通知断言。
- 不修改生产代码、不降低权限断言、不把 Mapper 或投递处理器替换为 mock；不运行 Maven，留由主线程串行验证。

### State Delta（proposal）

仅向主线程提议：在 `IMPLEMENTED` criterion 下登记 A3 测试夹具修复，证据为本报告和
`.ai/runtime/results/DISPATCH-20260921-TSW-RELIABILITY-A3.json`。未写入
`.ai/state/20260921-team-search-watch.yaml`。

### 风险

- 按 dispatch 约束未运行 Maven，尚未取得本轮 H2 集成测试真实退出码，不能据此宣称测试通过。
- 若主线程集成环境中的 MyBatis-Plus 逻辑删除配置与 test profile 不一致，撤权夹具可能暴露 schema 或配置差异；当前 test profile 已声明 `deleted` 逻辑删除配置。
- 生产代码仍由主线程和其他 TASK 负责，本次只修复白名单测试夹具。

### 下一步

主线程串行运行 `st-team` 相关 testCompile/H2 测试，以真实日志确认两处失败消失，并继续执行既定代码、安全和 schema 门禁。

### 变更影响

仅修改 `FileWatchReliabilityIntegrationTest` 的租户上下文恢复、真实 owner 更新确认和真实成员逻辑删除确认；生产服务、State、schema 及其他模块未修改。
