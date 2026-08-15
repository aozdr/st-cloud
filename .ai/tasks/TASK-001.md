# TASK：TASK-001 事件消费者异常重抛

> 依据《code-and-security-review.md》遗留建议 ①。优先级 P2。

## 元信息
- Task ID: `TASK-001`
- 关联 State: `.ai/state/20260812-review-followups.yaml`
- 归属 Agent: backend-engineer

## 目标
`SyncChangeMessageConsumer.onMessage` 当前对非幂等冲突类异常仅 log 不重抛，会导致消息被 ACK 而丢失（MQ at-least-once 依赖抛异常重投）。需区分：唯一键冲突（幂等跳过）与其它异常（重抛触发 MQ 重投）。

## 修改范围
- `st-sync/.../listener/SyncChangeMessageConsumer.java`：`onMessage` catch 逻辑改为——`DuplicateKeyException`（或幂等查询已处理）安全跳过；其它异常记录后重抛，交由 RocketMQ 重投
- 新增对应单元测试（与 TASK-004 同步补充 st-sync 测试基建）

## 禁止修改范围
- 不改事件业务语义、不改同步日志写入逻辑
- 不改 MQ 配置/主题约定

## 验收标准
- 唯一键冲突（重复投递）静默跳过，不抛异常
- 非唯一键异常抛给 MQ 触发重投，不静默丢失
- 原有幂等语义（event_log_id）保持

## 测试要求
- 单测：新写成功、重复跳过、异常重抛三类路径（Mockito，后续 TASK-004 建立基建）

## 输出要求
- 完成后产出 `.ai/docs/20260812-review-followups/changereport-t001.md`
