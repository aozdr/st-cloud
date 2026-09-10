# Change Report：TASK-001 事件消费者异常重抛

> 关联 Task: .ai/tasks/TASK-001.md  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| st-sync/.../listener/SyncChangeMessageConsumer.java | 修改 | onMessage catch 拆分：DuplicateKeyException 幂等跳过，其它异常重抛 |
| st-sync/src/test/.../SyncChangeMessageConsumerTest.java | 新增 | 5 条路径单测（新写/幂等跳过/唯一键冲突/异常重抛/空消息） |

## 与验收标准对照
- [x] 唯一键冲突（重复投递）静默跳过，不抛异常 — DuplicateKeyException catch 仅 debug 日志
- [x] 非唯一键异常抛给 MQ 触发重投，不静默丢失 — catch (Exception e) { throw e; }
- [x] 原有幂等语义（event_log_id）保持 — lreadyProcessed 预检 + uk_event_log_id 兜底

## 测试结果
- SyncChangeMessageConsumerTest：5 用例全通过
- 全量回归 124 用例 0 失败

## 风险
- 重投放大受 eventLogId 幂等约束，重复投递最多触发一次幂等跳过
