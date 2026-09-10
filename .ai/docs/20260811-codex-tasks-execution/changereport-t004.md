# Change Report：TASK-004 事件可靠性（Outbox + RocketMQ）

> 编码完成后的变更汇总。归属 exitCriteria：IMPLEMENTED。产出者：backend-engineer。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 背景
原 `FileIndexEvent` / `SyncChangeEvent` 通过 Spring `ApplicationEvent` 同进程发布，无持久化：进程崩溃 / 重启即丢事件，且 ES 索引与同步日志依赖事件驱动，存在丢事件后「索引与数据不一致」风险。TASK-004 引入 **Outbox 模式**：业务事务内写 `event_log`，事务提交后投递 RocketMQ，消费端按 `event_log_id` 幂等，ES 最终一致、MQ 失败可重投。

## 修改文件清单
**新增**
- `docker/mysql/init/29_event_log.sql` — `event_log` 表（id / event_type / payload / status / retry_count / processed_at）
- `docker/mysql/init/30_sync_change_log_event_log_id.sql` — `sync_change_log` 加 `event_log_id` 列 + 唯一键（MQ 消费幂等）
- `docker/mysql/init/26_sync_change_log.sql` — 基表定义同步补 `event_log_id` 列（新库直达）
- `st-core/.../event/EventMessage.java` — 可序列化事件负载（eventType / actionType / changeType / oldPath / eventLogId / FileNode 快照）
- `st-core/.../event/OutboxRelayEvent.java` — 事务提交后触发投递的内部事件
- `st-core/.../event/ReliableEventPublisher.java` — 可靠发布门面：事务内写 Outbox + 双通道（MQ 配置走 MQ / 未配置本地兜底）
- `st-core/.../outbox/EventRelay.java` — `@TransactionalEventListener(AFTER_COMMIT)` 投递 RocketMQ，成功标 1 / 失败标 2
- `st-core/.../outbox/EventRetryTask.java` — 定时扫描 `status=2` 且 `retry_count<5` 重投
- `st-core/.../entity/EventLog.java` + `st-core/.../mapper/EventLogMapper.java` — Outbox 实体与 `markSent/markFailed/selectRetryable`
- `st-search/.../listener/FileIndexMessageConsumer.java` — `FILE_INDEX` MQ 消费者（ES 索引/删除/元数据，幂等）
- `st-sync/.../listener/SyncChangeMessageConsumer.java` — `SYNC_CHANGE` MQ 消费者（写同步日志 + WebSocket，按 event_log_id 幂等）
- `st-core/src/test/.../EventOutboxIntegrationTest.java` — 6 用例集成测试

**修改**
- `st-core/.../service/impl/FileServiceImpl.java` / `RecycleBinServiceImpl.java` / `VersionServiceImpl.java` / `upload/UploadEventPublisher.java` — 30 处发布点改用 `ReliableEventPublisher`
- `st-common/.../config/MyBatisPlusConfig.java` — `event_log` 加入租户忽略表（系统级 Outbox，跨租户重投）
- `st-sync/.../entity/SyncChangeLog.java` — 新增 `eventLogId` 字段
- `st-core/src/test/resources/schema.sql` — 补 `event_log`、`sync_change_log`（幂等测试）
- `st-core/src/test/.../UploadStateMachineIntegrationTest.java` — `UploadEventPublisher` 构造适配（注入 mock `ReliableEventPublisher`）

## 与 TASK 验收标准对照
| 验收标准 | 实现 | 状态 |
|---|---|---|
| 事务回滚不产生事件 | Outbox 行在业务事务内写入，回滚一并回滚；`rollback_doesNotProduceEvent` 断言 REQUIRES_NEW 回滚后 `event_log` 计数 0 | ✅ |
| MQ 失败可重投 | `EventRelay` 失败标 `status=2`，`EventRetryTask` 重投；`relayFailure_markedFailed_thenRetryRecovers` 验证失败累计重试、恢复后标 1 | ✅ |
| 消费重复安全（幂等） | st-sync：`sync_change_log.event_log_id` 唯一键 + 插入前查；`syncChangeLog_idempotentByEventLogId` 断言重复插入被拦截；st-search：ES INDEX 覆盖写 / DELETE 幂等删除 | ✅ |
| ES 最终一致（重投后索引正确） | 重投链路复用 `SearchService.indexFile/removeIndex/updateMeta`；`relay_sendsAfterCommit` 验证负载往返字段一致 | ✅ |
| 事务内保留本地 publish 兜底 | MQ 未配置时 `ReliableEventPublisher` 仍发本地事件（`mqDisabled_publishesLocalFallback_andWritesOutbox`）；MQ 配置时仅 Outbox 不重复本地（`mqEnabled_skipsLocalEvent_onlyOutbox`） | ✅ |

## 测试结果
- `mvn test -pl st-core -am`：退出码 0，**28 个测试全绿**（含新增 EventOutboxIntegrationTest 6 用例）
- 全模块 `mvn compile`：BUILD SUCCESS
- `.ai/scripts/verify-loop.ps1`：PASS（FAIL=0，4 个 WARN 为旧版对比的合法表述）
- TASK-001/002/003 测试回归全部通过

## 明确未改动项（符合 TASK 禁止范围）
- 事件业务语义不变（INDEX/DELETE/UPDATE_META；CREATE/UPDATE/MOVE/RENAME/DELETE）
- 对外 REST 接口契约不变；前端 st-web 未触碰
- 本地 `@EventListener` 监听器保留作 MQ 未配置时的兜底，未删除
- 不改变既有 `sync_change_log` 自增 id 同步游标语义（`event_log_id` 仅作幂等键）

## 风险
- **MQ 主通道**：`rocketmq.name-server` 已配置即走 Outbox + MQ，broker 短暂不可用期间事件经重投补偿（最终一致，不丢）；本地兜底仅覆盖 MQ 未配置场景，broker 故障窗口内不实时处理
- **多实例并发重投**：定时任务多实例下同一行可能被重复投递，消费端按 `event_log_id` 幂等兜底
- **event_log 增长**：Outbox 行长期留存不清理，属设计选择（可审计）；后续可加归档/清理策略（非本 TASK 范围）
- **event_log 系统级表**：跨租户重投依赖 payload 内 tenantId 快照，租户隔离在消费端按业务字段保证
