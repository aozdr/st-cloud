# ADR-004：事件 Outbox + RocketMQ 可靠投递

> 架构决策记录。本决策定义 TASK-004 的事件可靠性方案。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
`FileIndexEvent`（st-search ES 索引）与 `SyncChangeEvent`（st-sync 同步日志）原先仅靠 Spring `ApplicationEvent` 同进程发布：事件无持久化，进程崩溃/重启即丢失；发布与业务同事务但消费异步，回滚场景依赖语义约定；无重试与幂等保障。需在不改事件业务语义、不改对外接口契约的前提下，使事件投递可靠、消费幂等、失败可恢复，同时保留无 MQ 环境下的本地兜底。

## 决策
- **Outbox 模式**：业务事务内先写 `event_log`（status=0，payload 为 `EventMessage` JSON），事务回滚即无事件；事务提交后由 `EventRelay`（`@TransactionalEventListener(AFTER_COMMIT)`）投递 RocketMQ，成功标 `status=1`，失败标 `status=2`
- **失败重投**：`EventRetryTask` 定时扫描 `status=2` 且 `retry_count<5` 的事件重投，成功标 1，重试耗尽后不再选中并告警
- **双通道兼容**：`rocketmq.name-server` 配置时走 Outbox + MQ（本地监听器不触发，避免重复消费）；未配置时事务内保留本地 `ApplicationEvent` 兜底（原 `@EventListener` 消费，降级不阻塞主流程）
- **消费者幂等**：st-sync 以 `sync_change_log.event_log_id` 唯一键（先查后插，唯一键兜底并发竞态）；st-search 依赖 ES 幂等语义（INDEX 覆盖写 / DELETE 幂等删除 / UPDATE_META 覆盖字段）
- **事件负载**：`EventMessage` 携带 eventType/actionType/changeType/oldPath/eventLogId 与 `FileNode` 业务快照，消费端还原复用现有 `SearchService` / 同步日志写入逻辑
- **租户隔离**：`event_log` 加入租户忽略表（系统级 Outbox），重投任务按全量扫描，租户信息随 payload 快照传递到消费端

## 放弃的方案
1. **仅本地 ApplicationEvent**：无持久化，进程崩溃即丢事件，无法保证 ES/同步与数据一致
2. **事务内直接发 MQ**：消息在事务提交前已发送，回滚会导致「无业务但有消息」的不一致；且 MQ 不可用时阻塞业务事务
3. **仅消费者幂等、不落 Outbox**：无法覆盖发布侧进程崩溃丢失（消息从未发出）的场景
4. **事务外异步扫表补发**：与业务事务解耦，存在事件与业务不一致的时间窗，复杂度高于事务内写 Outbox

## 理由
- **事务边界一致性**：Outbox 行与业务数据同事务提交/回滚，从根上保证「有业务才有事件、回滚即无事件」
- **可靠投递**：MQ 提供跨进程异步投递与重试；`event_log` 落库提供失败重投与可审计依据
- **幂等消费**：`event_log_id` 作为全局唯一幂等键，支持 MQ 至少一次投递语义下的重复安全
- **平滑降级**：未配置 MQ 的环境（本地开发/测试）保持原本地事件链路，零配置可运行
- **最小侵入**：30 处发布点仅替换调用门面，事件语义、监听器、接口契约均不变

## 后续限制
- **MQ 为主通道**：broker 短暂不可用窗口内事件延迟处理（不丢，最终一致）；本地兜底不覆盖「MQ 已配置但 broker 故障」场景
- **event_log 长期留存**：Outbox 行不自动清理（可审计）；后续可增加归档/清理策略
- **多实例重投**：定时任务多实例可能对同一行重复投递，依赖消费端幂等（已在设计内）
- **主题约定**：事件类型即主题（`FILE_INDEX` / `SYNC_CHANGE`），topic 变更需同步调整生产/消费两侧
